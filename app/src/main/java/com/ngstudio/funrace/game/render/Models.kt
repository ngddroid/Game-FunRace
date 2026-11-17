package com.ngstudio.funrace.game.render

import android.content.res.AssetManager
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Rest-pose bone of a skinned model (bones use identity rest orientation in model space). */
class Bone(val parent: Int, val hx: Float, val hy: Float, val hz: Float)

class ModelData(val name: String, val mesh: MeshData, val bones: List<Bone>)

/**
 * Loads `.dmesh` files written by tools/blender/export_dmesh.py.
 *
 * Layout (little-endian): "DMSH", u32 version, u32 flags(1=skinned), u32 vertexCount,
 * u32 indexCount, 3f boundsMin, 3f boundsMax, [u32 boneCount, boneCount x (i32 parent, 3f head)],
 * vertexCount x stride bytes (24 or 32, identical to the GPU layout), indexCount x u32.
 */
object DMesh {
    fun parse(name: String, bytes: ByteArray): ModelData {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4); b.get(magic)
        require(String(magic) == "DMSH") { "Bad dmesh magic in $name" }
        b.int // version
        val flags = b.int
        val vCount = b.int
        val iCount = b.int
        repeat(6) { b.float }
        val skinned = (flags and 1) != 0
        val bones = ArrayList<Bone>()
        if (skinned) {
            val n = b.int
            repeat(n) { bones += Bone(b.int, b.float, b.float, b.float) }
        }
        val md = MeshData(vCount.coerceAtLeast(1), iCount.coerceAtLeast(3), skinned)
        for (i in 0 until vCount) {
            md.pos[i * 3] = b.float; md.pos[i * 3 + 1] = b.float; md.pos[i * 3 + 2] = b.float
            b.get(md.nrm, i * 4, 4)
            b.get(md.col, i * 4, 4)
            b.get(md.mat, i * 4, 4)
            if (skinned) { b.get(md.joints, i * 4, 4); b.get(md.weights, i * 4, 4) }
        }
        md.vCount = vCount
        for (k in 0 until iCount) md.idx[k] = b.int
        md.iCount = iCount
        md.recomputeBounds()
        return ModelData(name, md, bones)
    }
}

/** CPU cache of every model in assets/models (they are small; loaded once per process). */
object ModelLibrary {
    private val cache = HashMap<String, ModelData>()
    @Volatile var assets: AssetManager? = null

    @Synchronized
    fun get(name: String): ModelData {
        cache[name]?.let { return it }
        val am = assets
        val md = try {
            requireNotNull(am) { "ModelLibrary.assets not set" }
            val bytes = am.open("models/$name.dmesh").use { it.readBytes() }
            DMesh.parse(name, bytes)
        } catch (t: Throwable) {
            Log.w("ModelLibrary", "Missing model $name, using placeholder: ${t.message}")
            ModelData(name, Shapes.placeholder(), emptyList())
        }
        cache[name] = md
        return md
    }

    fun has(name: String): Boolean = try { assets?.open("models/$name.dmesh")?.close(); true } catch (_: Throwable) { false }

    /** Warms the cache off the GL thread (splash loading). */
    fun preload(names: Collection<String>) { for (n in names) get(n) }
}

/** GPU meshes for models, owned by one GL context. */
class MeshCache {
    private val meshes = HashMap<String, Mesh>()
    private val batches = HashMap<String, InstanceBatch>()

    fun mesh(name: String): Mesh = meshes.getOrPut(name) { Mesh(ModelLibrary.get(name).mesh) }

    /** One instance batch per model per context; cleared by the scene each frame. */
    fun batch(name: String): InstanceBatch = batches.getOrPut(name) { InstanceBatch(mesh(name), 16) }

    fun allBatches(): Collection<InstanceBatch> = batches.values

    /** The batch for [name] if it was used this context (null otherwise; never creates one). */
    fun existing(name: String): InstanceBatch? = batches[name]

    fun clearBatches() { for (b in batches.values) b.clear() }

    fun release() {
        for (m in meshes.values) m.release()
        meshes.clear(); batches.clear()
    }
}
