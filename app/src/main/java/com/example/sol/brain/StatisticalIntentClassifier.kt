package com.example.sol.brain

import android.content.Context
import com.example.sol.memory.UserUtteranceExampleEntity
import com.example.sol.text.ColloquialLexicon
import com.example.sol.text.PersianTextNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ln
import kotlin.math.sqrt

data class StatisticalPrediction(
    val action: SolAction,
    val score: Float,
    val knnScore: Float,
    val centroidScore: Float,
)

class StatisticalIntentClassifier(
    private val context: Context? = null,
    private val config: NluConfig = NluConfig(),
    private val lexicon: ColloquialLexicon = if (context != null) ColloquialLexicon.fromContext(context) else ColloquialLexicon.loadDefault(),
    private val baseCorpusLoader: (() -> List<Pair<String, SolAction>>)? = null,
) {
    private class SparseVec(
        val indices: IntArray,
        val weights: FloatArray,
    ) {
        fun dot(other: SparseVec): Float {
            var i = 0
            var j = 0
            var sum = 0f
            val aIdx = indices
            val bIdx = other.indices
            val aWt = weights
            val bWt = other.weights
            while (i < aIdx.size && j < bIdx.size) {
                val ai = aIdx[i]
                val bj = bIdx[j]
                when {
                    ai == bj -> {
                        sum += aWt[i] * bWt[j]
                        i++
                        j++
                    }
                    ai < bj -> i++
                    else -> j++
                }
            }
            return sum
        }
    }

    private data class RawTrainingSample(
        val text: String,
        val normalizedText: String,
        val action: SolAction,
        val weight: Float,
        val userExampleId: Long? = null,
    )

    private data class IndexedSample(
        val action: SolAction,
        val vec: SparseVec,
        val weight: Float,
        val userExampleId: Long?,
    )

    private class ModelIndex(
        val vocab: Map<String, Int>,
        val idf: FloatArray,
        val samples: List<IndexedSample>,
        val centroids: Map<SolAction, SparseVec>,
    )

    private val initMutex = Mutex()

    @Volatile
    private var modelIndex: ModelIndex? = null

    @Volatile
    var lastBuildDurationMs: Long = 0L
        private set

    private val rawBaseSamples = mutableListOf<RawTrainingSample>()
    private val userSamples = mutableListOf<RawTrainingSample>()
    private var baseLoaded = false

    fun isInitialized(): Boolean = modelIndex != null

    suspend fun ensureInitialized(
        userExamplesProvider: (suspend () -> List<UserUtteranceExampleEntity>)? = null,
    ) {
        if (modelIndex != null) return
        initMutex.withLock {
            if (modelIndex != null) return@withLock
            val existingUser = userExamplesProvider?.invoke().orEmpty()
            withContext(Dispatchers.Default) {
                loadBaseIfNeeded()
                syncUserSamplesLocked(existingUser)
                rebuildIndexLocked()
            }
        }
    }

    fun initializeBlocking(userExamples: List<UserUtteranceExampleEntity> = emptyList()) {
        if (modelIndex != null && userExamples.isEmpty()) return
        synchronized(this) {
            loadBaseIfNeeded()
            syncUserSamplesLocked(userExamples)
            rebuildIndexLocked()
        }
    }

    suspend fun addUserExample(example: UserUtteranceExampleEntity) {
        initMutex.withLock {
            withContext(Dispatchers.Default) {
                loadBaseIfNeeded()
                val action = parseIntentLabel(example.intent) ?: return@withContext
                userSamples.removeAll { it.userExampleId == example.id && example.id > 0 }
                userSamples.add(
                    RawTrainingSample(
                        text = example.text,
                        normalizedText = example.normalizedText.ifBlank {
                            PersianTextNormalizer.normalizeForMatching(example.text)
                        },
                        action = action,
                        weight = config.userExampleBoost,
                        userExampleId = example.id,
                    )
                )
                rebuildIndexLocked()
            }
        }
    }

    suspend fun syncUserExamples(examples: List<UserUtteranceExampleEntity>) {
        initMutex.withLock {
            withContext(Dispatchers.Default) {
                loadBaseIfNeeded()
                syncUserSamplesLocked(examples)
                rebuildIndexLocked()
            }
        }
    }

    suspend fun removeUserExample(exampleId: Long) {
        initMutex.withLock {
            withContext(Dispatchers.Default) {
                loadBaseIfNeeded()
                if (userSamples.removeAll { it.userExampleId == exampleId }) {
                    rebuildIndexLocked()
                }
            }
        }
    }

    fun classify(raw: String, topK: Int = 3): List<StatisticalPrediction> {
        val idx = modelIndex ?: return emptyList()
        val queryVec = vectorizeQuery(raw, idx) ?: return emptyList()

        // Score top-K nearest neighbors using dot product
        val k = config.kNearest.coerceAtLeast(1)
        val topNeighbors = ArrayList<Pair<IndexedSample, Float>>(k + 1)
        for (sample in idx.samples) {
            val sim = queryVec.dot(sample.vec)
            if (sim <= 0.01f) continue
            if (topNeighbors.size < k) {
                topNeighbors.add(sample to sim)
                topNeighbors.sortBy { it.second }
            } else if (sim > topNeighbors[0].second) {
                topNeighbors[0] = sample to sim
                topNeighbors.sortBy { it.second }
            }
        }

        val knnVotes = HashMap<SolAction, Float>()
        val maxSimByAction = HashMap<SolAction, Float>()
        var totalVoteWeight = 0f
        for ((sample, sim) in topNeighbors) {
            val weighted = sim * sample.weight
            knnVotes[sample.action] = (knnVotes[sample.action] ?: 0f) + weighted
            val prevMax = maxSimByAction[sample.action] ?: 0f
            if (sim > prevMax) {
                maxSimByAction[sample.action] = sim
            }
            totalVoteWeight += weighted
        }

        val results = ArrayList<StatisticalPrediction>(idx.centroids.size)
        for ((action, centroid) in idx.centroids) {
            val voteShare = if (totalVoteWeight > 0f) (knnVotes[action] ?: 0f) / totalVoteWeight else 0f
            val bestSim = maxSimByAction[action] ?: 0f
            // Gate voteShare by actual max cosine similarity so weak partial overlaps cannot inflate to 1.0
            val similarityGate = (bestSim * 1.25f).coerceIn(0f, 1f)
            val knnScore = (voteShare * similarityGate).coerceIn(0f, 1f)
            val centroidScore = centroid.dot(queryVec).coerceIn(0f, 1f)
            val combined = (config.knnWeight * knnScore + config.centroidWeight * centroidScore).coerceIn(0f, 1f)
            if (combined > 0.01f) {
                results.add(
                    StatisticalPrediction(
                        action = action,
                        score = combined,
                        knnScore = knnScore,
                        centroidScore = centroidScore,
                    )
                )
            }
        }
        return results.sortedByDescending { it.score }.take(topK)
    }

    fun getTrainingSampleCount(): Int = (modelIndex?.samples?.size ?: 0)

    private fun syncUserSamplesLocked(examples: List<UserUtteranceExampleEntity>) {
        userSamples.clear()
        for (ex in examples) {
            val action = parseIntentLabel(ex.intent) ?: continue
            userSamples.add(
                RawTrainingSample(
                    text = ex.text,
                    normalizedText = ex.normalizedText.ifBlank {
                        PersianTextNormalizer.normalizeForMatching(ex.text)
                    },
                    action = action,
                    weight = config.userExampleBoost,
                    userExampleId = ex.id,
                )
            )
        }
    }

    private fun loadBaseIfNeeded() {
        if (baseLoaded) return
        val loaded = baseCorpusLoader?.invoke() ?: loadDefaultCorpus(context)
        rawBaseSamples.clear()
        for ((text, action) in loaded) {
            rawBaseSamples.add(
                RawTrainingSample(
                    text = text,
                    normalizedText = PersianTextNormalizer.normalizeForMatching(text),
                    action = action,
                    weight = 1.0f,
                )
            )
        }
        baseLoaded = true
    }

    private fun rebuildIndexLocked() {
        val startNs = System.nanoTime()
        val allSamples = rawBaseSamples + userSamples
        if (allSamples.isEmpty()) {
            modelIndex = ModelIndex(emptyMap(), FloatArray(0), emptyList(), emptyMap())
            return
        }

        // 1. Build vocabulary & document frequency
        val vocab = HashMap<String, Int>(4096)
        val docFreqList = ArrayList<Int>(4096)
        val docTokenMaps = ArrayList<Map<Int, Int>>(allSamples.size)

        for (sample in allSamples) {
            val features = extractFeatures(sample.text)
            val tf = HashMap<Int, Int>(features.size)
            val seenInDoc = HashSet<Int>(features.size)
            for (feat in features) {
                val id = vocab.getOrPut(feat) {
                    val newId = docFreqList.size
                    docFreqList.add(0)
                    newId
                }
                tf[id] = (tf[id] ?: 0) + 1
                if (seenInDoc.add(id)) {
                    docFreqList[id] = docFreqList[id] + 1
                }
            }
            docTokenMaps.add(tf)
        }

        val numDocs = allSamples.size.toFloat()
        val idf = FloatArray(docFreqList.size) { i ->
            (ln((numDocs + 1f) / (docFreqList[i] + 1f)) + 1f).toFloat()
        }

        // 2. Build normalized sparse TF-IDF vectors + accumulate centroids
        val indexedSamples = ArrayList<IndexedSample>(allSamples.size)
        val centroidAccum = HashMap<SolAction, FloatArray>()

        for (i in allSamples.indices) {
            val sample = allSamples[i]
            val vec = toNormalizedSparseVec(docTokenMaps[i], idf) ?: continue
            indexedSamples.add(
                IndexedSample(
                    action = sample.action,
                    vec = vec,
                    weight = sample.weight,
                    userExampleId = sample.userExampleId,
                )
            )
            val accum = centroidAccum.getOrPut(sample.action) { FloatArray(idf.size) }
            val w = sample.weight
            for (k in vec.indices.indices) {
                val fid = vec.indices[k]
                accum[fid] += vec.weights[k] * w
            }
        }

        // 3. L2-normalize centroids into compact sparse vectors
        val centroids = HashMap<SolAction, SparseVec>(centroidAccum.size)
        for ((action, dense) in centroidAccum) {
            var normSq = 0f
            var nonZero = 0
            for (v in dense) {
                if (v > 0f) {
                    normSq += v * v
                    nonZero++
                }
            }
            if (nonZero == 0 || normSq <= 0f) continue
            val invNorm = 1f / sqrt(normSq)
            val idxArr = IntArray(nonZero)
            val wtArr = FloatArray(nonZero)
            var ptr = 0
            for (fid in dense.indices) {
                val v = dense[fid]
                if (v > 0f) {
                    idxArr[ptr] = fid
                    wtArr[ptr] = v * invNorm
                    ptr++
                }
            }
            centroids[action] = SparseVec(idxArr, wtArr)
        }

        modelIndex = ModelIndex(
            vocab = vocab,
            idf = idf,
            samples = indexedSamples,
            centroids = centroids,
        )
        lastBuildDurationMs = (System.nanoTime() - startNs) / 1_000_000L
    }

    private fun vectorizeQuery(raw: String, idx: ModelIndex): SparseVec? {
        val features = extractFeatures(raw)
        if (features.isEmpty()) return null
        val tf = HashMap<Int, Int>(features.size)
        for (feat in features) {
            val id = idx.vocab[feat] ?: continue
            tf[id] = (tf[id] ?: 0) + 1
        }
        return toNormalizedSparseVec(tf, idx.idf)
    }

    private fun toNormalizedSparseVec(tf: Map<Int, Int>, idf: FloatArray): SparseVec? {
        if (tf.isEmpty()) return null
        val sortedIds = tf.keys.sorted()
        val idxArr = IntArray(sortedIds.size)
        val wtArr = FloatArray(sortedIds.size)
        var normSq = 0f
        for (i in sortedIds.indices) {
            val fid = sortedIds[i]
            val count = tf[fid] ?: 0
            val w = (1f + ln(count.toFloat())) * idf[fid]
            idxArr[i] = fid
            wtArr[i] = w
            normSq += w * w
        }
        if (normSq <= 0f) return null
        val invNorm = 1f / sqrt(normSq)
        for (i in wtArr.indices) {
            wtArr[i] *= invNorm
        }
        return SparseVec(idxArr, wtArr)
    }

    private fun extractFeatures(raw: String): List<String> {
        val rawTokens = PersianTextNormalizer.tokenizeRaw(raw)
        val formalTokens = lexicon.applyToTokens(rawTokens)
        val norm = formalTokens.joinToString(" ")
        if (norm.isBlank()) return emptyList()

        val features = ArrayList<String>(norm.length * 3 + formalTokens.size * 2)
        // Word unigrams & bigrams (with formal tokens)
        for (i in formalTokens.indices) {
            val tok = formalTokens[i]
            features.add("w:$tok")
            if (i + 1 < formalTokens.size) {
                features.add("b:${tok}_${formalTokens[i + 1]}")
            }
        }
        // Raw tokens too if colloquial word differs
        for (rt in rawTokens) {
            features.add("w:$rt")
        }
        // Char n-grams (min..max)
        val padded = "^$norm$"
        for (n in config.minCharNgram..config.maxCharNgram) {
            if (padded.length < n) continue
            val limit = padded.length - n
            for (i in 0..limit) {
                features.add(padded.substring(i, i + n))
            }
        }
        return features
    }

    companion object {
        private const val ASSET_PATH = "nlu/intents_fa.jsonl"

        fun parseIntentLabel(label: String): SolAction? {
            if (label == "OUT_OF_SCOPE") return SolAction.UNKNOWN
            return runCatching { SolAction.valueOf(label) }.getOrNull()
        }

        fun loadDefaultCorpus(context: Context?): List<Pair<String, SolAction>> {
            val lines = runCatching {
                context?.assets?.open(ASSET_PATH)?.bufferedReader(Charsets.UTF_8)?.use { it.readLines() }
            }.getOrNull() ?: run {
                val fileCandidates = listOf(
                    File("app/src/main/assets/$ASSET_PATH"),
                    File("src/main/assets/$ASSET_PATH"),
                )
                fileCandidates.firstOrNull { it.isFile }?.readLines(Charsets.UTF_8)
            }.orEmpty()

            val textRegex = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            val intentRegex = Regex(""""intent"\s*:\s*"((?:[^"\\]|\\.)*)"""")

            val result = ArrayList<Pair<String, SolAction>>(lines.size)
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val text = textRegex.find(line)?.groupValues?.getOrNull(1) ?: continue
                val intentStr = intentRegex.find(line)?.groupValues?.getOrNull(1) ?: continue
                val action = parseIntentLabel(intentStr) ?: continue
                result.add(text to action)
            }
            return result
        }
    }
}
