package sillage

import sillage.math.Mat4
import sillage.math.Vec3
import sillage.vk.*
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
import sillage.ui.GlyphAtlas
import sillage.ui.HUD_FLOATS
import kotlin.math.sqrt

/** What the renderer needs from a window: instance extensions, a VkSurfaceKHR and the drawable size. */
internal interface SurfaceSource {
    val instanceExtensions: List<String>
    fun createSurface(vk: VkInstanceApi, stack: NativeStack): Long
    /** Drawable size in pixels as (width, height); zero while minimised. */
    fun framebufferSize(): IntArray
}

/**
 * Vulkan renderer called through FFM. Two frames in flight: the CPU records the next frame while the GPU
 * draws the last one (each frame has its command buffer, fence, uniform block and host buffers). Every copy
 * of a mesh is drawn in one call, its model matrices in an instance buffer. A frame is drawn in passes: the
 * shadow map; the scene in linear light (HDR), with the distance to the eye in alpha; the glow, down and up
 * a chain of smaller images; and the finished picture (ink, glow, tone, vignette) with the interface over it.
 * The same passes work with a window or without any display server.
 */
private const val FRAMES = 2
/** Side of the shadow map in texels. */
internal const val SHADOW_TEXELS = 2048
/** Push constants: view × projection, eye and time, the shadow map's centre and half side, the water's two colours. */
private const val PUSH_BYTES = 128
/** Point lights handed to the shaders each frame (the nearest to the camera). */
internal const val MAX_LIGHTS = 16
/** Bytes of the frame's uniform block (frame.glsl): fourteen vectors, then the lights' places and colours. */
/** Shadow squares one inside the other, each with its layer in the shadow map (see Lighting.shadowCascades). */
internal const val CASCADES = 3
private const val FRAME_BYTES = (14 + CASCADES + 2 * MAX_LIGHTS) * 16
/** Levels of the glow: half the picture's size, then each half the last. */
private const val BLOOM_LEVELS = 5
/** Floats per particle drawn: centre and size (negative: it glows), colour and opacity. */
internal const val PARTICLE_FLOATS = 8
// Blending: colour source and destination factors, then alpha's. The alpha of the scene image holds the
// distance to the eye, which blended things leave as it was.
private val BLEND_KEEP_ALPHA = intArrayOf(VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_FACTOR_ZERO, VK_BLEND_FACTOR_ONE)
/** Formats the interface needs that the scene does not (VkFormat values). */
private const val HUD_FORMAT_R8 = 9
private const val HUD_FORMAT_RG32F = 103
private val BLEND_PREMULTIPLIED = intArrayOf(VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_FACTOR_ZERO, VK_BLEND_FACTOR_ONE)

/** What a frame draws besides the nodes: the point lights near the camera and the particles. */
internal class FrameExtras {
    /** Per light: position, radius, colour × intensity, unused (8 floats). */
    val lights = FloatArray(8 * MAX_LIGHTS)
    var lightCount = 0
    /** Per particle [PARTICLE_FLOATS] floats. */
    var particles = FloatArray(PARTICLE_FLOATS * 256)
    var particleCount = 0
    /** Debug lines: two vertices per segment, [LINE_FLOATS] floats each (position, colour and opacity). */
    var lines = FloatArray(LINE_FLOATS * 1024)
    var lineVertexCount = 0
}

/** Floats per debug line vertex: position, colour and opacity. */
internal const val LINE_FLOATS = 7

internal class VulkanRenderer(private val target: SurfaceSource? = null, private val validation: Boolean = false,
                              largeurHorsEcran: Int = 1280, hauteurHorsEcran: Int = 800,
                              /** Whether the scenes drawn have cast shadows (the map is made with the renderer). */
                              shadows: Boolean = true) : AutoCloseable {
    /** Whether this renderer has the shadow maps (made with it; without, the scenes are drawn without cast shadows). */
    val shadowMaps=shadows
    /** Taille de rendu : celle de la fenêtre (suivie au redimensionnement), sinon celle demandée hors écran. */
    var largeur=largeurHorsEcran
        private set
    var hauteur=hauteurHorsEcran
        private set
    /** Échantillons par pixel (anticrénelage MSAA) : 4 si le GPU le permet. */
    var echantillons=1
        private set
    private val arena=Arena.ofConfined()
    private val stack=NativeStack(1L shl 20, arena)
    private lateinit var vk: VkInstanceApi
    private lateinit var dev: VkDeviceApi
    private var physical: MemorySegment=MemorySegment.NULL
    private var queue: MemorySegment=MemorySegment.NULL
    private var family=0
    private var surface=0L
    private var swapchain=0L
    private var swapImages=LongArray(0)
    private var swapWidth=0
    private var swapHeight=0
    private val colorFormat=VK_FORMAT_R8G8B8A8_UNORM
    /** The scene and the glow, in linear light; the scene's alpha is the distance to the eye. */
    private val hdrFormat=VK_FORMAT_R16G16B16A16_SFLOAT
    private var memoryFlags=IntArray(0)
    private var pool=0L
    private var renderPass=0L          // the scene, in linear light
    private var postPass=0L            // one image made from others (the glow's levels)
    private var compositePass=0L       // the finished picture, then the interface
    private var framebuffer=0L
    private var compositeFramebuffer=0L
    private var layout=0L
    private var postLayout=0L
    private var pipeline=0L
    private var pipelineSky=0L
    private var pipelineOmbre=0L
    private var pipelineWater=0L
    private var pipelineParticles=0L
    private var pipelineLines=0L
    private var pipelineLinesXray=0L
    private var pipelineHud=0L
    private var pipelineBright=0L
    private var pipelineDown=0L
    private var pipelineUp=0L
    private var pipelineComposite=0L
    // Cast shadows: a depth map seen from the main light, drawn first each frame, then sampled by the shaders.
    private val shadowSize=if(shadows) SHADOW_TEXELS else 1
    private var shadowMap: Image?=null
    private var shadowPass=0L
    private var shadowFramebuffers=LongArray(0)
    private var shadowLayerViews=LongArray(0)
    private var shadowPipeline=0L
    private var shadowPipelineCut=0L          // the same with shadow.frag: only for meshes cut out by an image's alpha
    private var sampler=0L                     // compares depths in the shadow map
    private var repeat=0L                      // reads the detail textures: repeating, filtered between levels
    // Detail textures (Texture): one layer each, every level, in one array image; made again when new textures appear.
    private var textures: Image?=null
    private var textureCount=-1                // textures in the array (-1: none yet, the neutral placeholder)
    private var texturesPainted=false          // all of them painted, none left neutral
    private var uploads: MemorySegment=MemorySegment.NULL   // a command buffer for sending textures
    private var linear=0L                      // reads the passes' images, filtered, clamped at the edges
    private var setLayout=0L
    private var postSetLayout=0L
    private var descriptorPool=0L
    private val postSets=LongArray(2*BLOOM_LEVELS)
    private class Frame(val command: MemorySegment, val fence: Long, val acquired: Long) {
        var hud: Buffer?=null; var hudSent: HudMesh?=null
        var instances: Buffer?=null; var mapped: MemorySegment=MemorySegment.NULL
        var uniform: Buffer?=null; var uniformMapped: MemorySegment=MemorySegment.NULL; var set=0L
        var timed=false
    }
    private val frames=ArrayList<Frame>()
    private var frameIndex=0
    private var finished=LongArray(0)          // per swapchain image: presentation waits on it
    // Waiting for the last frame to be on screen before reading the keys for the next one (VK_KHR_present_wait):
    // one frame queued instead of three, so a key acts on the very next picture. Off where the driver lacks it.
    var attenteAffichage=false
        private set
    private var presents=0L                    // presents numbered so far on this swapchain (VkPresentIdKHR)
    private var pret=false                     // this frame's fence waited and its swapchain image acquired (attendre)
    private var image=-1
    private var queries=0L                     // two timestamps per frame in flight
    private var timestampBits=0
    private var timestampPeriod=0f
    /** GPU time of the last finished frame in milliseconds (0 if the GPU cannot time its work). */
    var gpuMs=0.0
        private set
    private var messenger=0L
    var erreursValidation=0
        private set
    private class Image(val image: Long, val memory: Long, val view: Long)
    private class Buffer(val buffer: Long, val memory: Long, val size: Long)
    private var color: Image?=null          // the finished picture, copied to the window or read for a capture
    private var hdr: Image?=null            // the scene in linear light (resolved), read by the later passes
    private var msaa: Image?=null           // the multisampled scene image where it is drawn
    private var depth: Image?=null
    private val bloomDown=arrayOfNulls<Image>(BLOOM_LEVELS)
    private val bloomUp=arrayOfNulls<Image>(BLOOM_LEVELS-1)
    private val downFramebuffers=LongArray(BLOOM_LEVELS)
    private val upFramebuffers=LongArray(BLOOM_LEVELS-1)
    private val meshes=IdentityHashMap<Mesh,Buffer>()
    private val effectBuffers=IdentityHashMap<Mesh,Buffer>()   // per-vertex light and sway, for the meshes that have them
    // Meshes whose vertices change every frame (skinned parts): a buffer per frame in flight, sent again when their version moved.
    private class Dynamic(val buffers: Array<Buffer>, val versions: IntArray)
    private val dynamicMeshes=IdentityHashMap<Mesh,Dynamic>()
    private var zero: Buffer?=null                             // zeros, read as the effects of every other mesh
    /** Copies of one mesh to draw this frame: their model rows (12 floats each), reused from frame to frame. */
    private class Batch(val mesh: Mesh) { var count=0; var rows=FloatArray(12*8); var start=0L; var water: WaterLook?=null }
    private val batches=IdentityHashMap<Mesh,Batch>()            // copies that cast a shadow
    private val quietBatches=IdentityHashMap<Mesh,Batch>()       // copies that do not (decals, unlit things…)
    private val skyBatches=IdentityHashMap<Mesh,Batch>()
    private val shadowBatches=IdentityHashMap<Mesh,Batch>()      // blob shadows
    private val waterBatches=IdentityHashMap<Mesh,Batch>()
    private val casterBatches=Array(CASCADES) { IdentityHashMap<Mesh,Batch>() }   // copies drawn into each cascade's map
    private val casters=Array(CASCADES) { ArrayList<Batch>() }
    private val opaque=ArrayList<Batch>()
    private val quiet=ArrayList<Batch>()
    private val skies=ArrayList<Batch>()
    private val shadows=ArrayList<Batch>()
    private val waters=ArrayList<Batch>()
    private val defaultLighting=Lighting()
    private var ferme=false
    // SILLAGE_LATENCE=1: once a second, how long a key takes to reach the screen and where a frame waits.
    private val diagnostic=System.getenv("SILLAGE_LATENCE")!=null
    private var readAt=0L; private var latShown=0L; private var latShownCount=0
    private var latPresentWait=0L; private var latFence=0L; private var latAcquire=0L; private var latPresent=0L; private var latFrames=0; private var latSince=System.nanoTime()
    var nomGpu=""
        private set

    fun ouvrir() {
        try {
            createInstance()
            if(target != null) surface=target.createSurface(vk,stack)
            chooseDevice()
            createDevice()
            stack.frame { s ->
                val out=s.allocate(JAVA_LONG)
                dev.createCommandPool(s.struct(VkCommandPoolCreateInfo,
                    "flags" to VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, "queueFamilyIndex" to family),out)
                pool=out.get(JAVA_LONG,0)
                val cmd=s.allocate(ADDRESS,FRAMES+1L)
                dev.allocateCommandBuffers(s.struct(VkCommandBufferAllocateInfo,
                    "commandPool" to pool, "level" to VK_COMMAND_BUFFER_LEVEL_PRIMARY, "commandBufferCount" to FRAMES+1),cmd)
                uploads=cmd.getAtIndex(ADDRESS,FRAMES.toLong())
                for(f in 0 until FRAMES) {
                    dev.createFence(s.struct(VkFenceCreateInfo, "flags" to VK_FENCE_CREATE_SIGNALED_BIT),out); val fence=out.get(JAVA_LONG,0)
                    dev.createSemaphore(s.struct(VkSemaphoreCreateInfo),out); val acquired=out.get(JAVA_LONG,0)
                    frames.add(Frame(cmd.getAtIndex(ADDRESS,f.toLong()),fence,acquired).also {
                        it.hud=buffer(4L*1024*1024,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT); growInstances(it,16*1024)
                        val u=buffer(FRAME_BYTES.toLong(),VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT); it.uniform=u; it.uniformMapped=map(u) })
                }
                if(timestampBits>0 && timestampPeriod>0f) {
                    dev.createQueryPool(s.struct(VkQueryPoolCreateInfo, "queryType" to VK_QUERY_TYPE_TIMESTAMP, "queryCount" to 2*FRAMES),out)
                    queries=out.get(JAVA_LONG,0)
                }
            }
            growZero(64*1024)
            createRenderPasses()
            createShadowResources()
            createDescriptors()
            hudGpu.create()
            syncTextures(target==null)
            createPipelines()
            if(surface != 0L) recreateSwapchain() else createTargets()
            println("SILLAGE_VULKAN_OK GPU=" + nomGpu + " target=" + largeur + "x" + hauteur + " msaa=" + echantillons + " validation=" + validation)
        } catch(e: Throwable) { close(); throw e }
    }

    private fun createInstance() = stack.frame { s ->
        if(validation) require("VK_LAYER_KHRONOS_validation" in VkLoader.layerNames()) {
            "Installer vulkan-validation-layers pour utiliser --validation" }
        val extensions=(target?.instanceExtensions ?: emptyList()) + (if(validation) listOf(VK_EXT_DEBUG_UTILS_EXTENSION_NAME) else emptyList())
        val app=s.struct(VkApplicationInfo, "pApplicationName" to s.allocateFrom("Sillage"),
            "pEngineName" to s.allocateFrom("Sillage Kotlin"), "apiVersion" to VK_API_VERSION_1_0)
        val ci=s.struct(VkInstanceCreateInfo, "pApplicationInfo" to app)
        if(extensions.isNotEmpty()) ci.put(VkInstanceCreateInfo,
            "enabledExtensionCount" to extensions.size, "ppEnabledExtensionNames" to s.strings(extensions))
        if(validation) ci.put(VkInstanceCreateInfo,
            "enabledLayerCount" to 1, "ppEnabledLayerNames" to s.strings(listOf("VK_LAYER_KHRONOS_validation")))
        val out=s.allocate(ADDRESS); VkLoader.createInstance(ci,out); vk=VkInstanceApi(out.get(ADDRESS,0))
        if(validation) {
            val target=MethodHandles.lookup().findVirtual(VulkanRenderer::class.java,"onDebugMessage",
                MethodType.methodType(Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,
                    MemorySegment::class.java,MemorySegment::class.java)).bindTo(this)
            val callback=Linker.nativeLinker().upcallStub(target,FunctionDescriptor.of(JAVA_INT,JAVA_INT,JAVA_INT,ADDRESS,ADDRESS),arena)
            val di=s.struct(VkDebugUtilsMessengerCreateInfoEXT,
                "messageSeverity" to (VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT),
                "messageType" to (VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT),
                "pfnUserCallback" to callback)
            val m=s.allocate(JAVA_LONG); vk.createDebugUtilsMessengerEXT(di,m); messenger=m.get(JAVA_LONG,0)
        }
    }

    /** Appelé par la couche de validation, depuis le code natif : ne doit jamais lever d'exception. */
    @Suppress("unused")
    private fun onDebugMessage(severity: Int, types: Int, data: MemorySegment, user: MemorySegment): Int {
        try {
            val d=data.reinterpret(VkDebugUtilsMessengerCallbackDataEXT.size)
            val message=d.ptr(VkDebugUtilsMessengerCallbackDataEXT,"pMessage").reinterpret(Long.MAX_VALUE).getString(0)
            if(severity and VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT != 0) erreursValidation++
            System.err.println("Vulkan: " + message)
        } catch(_: Throwable) { erreursValidation++ }
        return VK_FALSE
    }

    private fun chooseDevice() = stack.frame { s ->
        val n=s.allocate(JAVA_INT); vk.enumeratePhysicalDevices(n,MemorySegment.NULL)
        require(n.get(JAVA_INT,0)>0) { "No Vulkan driver available" }
        val devices=s.allocate(ADDRESS,n.get(JAVA_INT,0).toLong()); vk.enumeratePhysicalDevices(n,devices)
        val props=s.struct(VkPhysicalDeviceProperties)
        var best=-1
        for(i in 0 until n.get(JAVA_INT,0)) {
            val candidate=devices.getAtIndex(ADDRESS,i.toLong())
            val count=s.allocate(JAVA_INT); vk.getPhysicalDeviceQueueFamilyProperties(candidate,count,MemorySegment.NULL)
            val families=s.structs(VkQueueFamilyProperties,count.get(JAVA_INT,0)); vk.getPhysicalDeviceQueueFamilyProperties(candidate,count,families)
            vk.getPhysicalDeviceProperties(candidate,props)
            for(j in 0 until count.get(JAVA_INT,0)) {
                if(families.at(VkQueueFamilyProperties,j).u32(VkQueueFamilyProperties,"queueFlags") and VK_QUEUE_GRAPHICS_BIT == 0) continue
                if(surface != 0L) {
                    val support=s.allocate(JAVA_INT); vk.getPhysicalDeviceSurfaceSupportKHR(candidate,j,surface,support)
                    if(support.get(JAVA_INT,0) != VK_TRUE) continue
                }
                val score=when(props.u32(VkPhysicalDeviceProperties,"deviceType")) {
                    VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU->100; VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU->50; else->1 }
                if(score>best) {
                    best=score; physical=candidate; family=j; nomGpu=props.chars(VkPhysicalDeviceProperties,"deviceName")
                    timestampBits=families.at(VkQueueFamilyProperties,j).u32(VkQueueFamilyProperties,"timestampValidBits")
                    timestampPeriod=props.f32(VkPhysicalDeviceProperties,"limits.timestampPeriod")
                    val counts=props.u32(VkPhysicalDeviceProperties,"limits.framebufferColorSampleCounts") and
                        props.u32(VkPhysicalDeviceProperties,"limits.framebufferDepthSampleCounts")
                    echantillons=if(counts and VK_SAMPLE_COUNT_4_BIT != 0) 4 else 1
                }
            }
        }
        require(best>=0) { "No Vulkan graphics queue can present to the window" }
        val fp=s.struct(VkFormatProperties); vk.getPhysicalDeviceFormatProperties(physical,VK_FORMAT_D32_SFLOAT,fp)
        require(fp.u32(VkFormatProperties,"optimalTilingFeatures") and VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT != 0) { "D32 depth format not supported" }
        val mp=s.struct(VkPhysicalDeviceMemoryProperties); vk.getPhysicalDeviceMemoryProperties(physical,mp)
        memoryFlags=IntArray(mp.u32(VkPhysicalDeviceMemoryProperties,"memoryTypeCount")) {
            mp.u32(VkPhysicalDeviceMemoryProperties,"memoryTypes[$it].propertyFlags") }
    }

    private fun createDevice() {
        val offered=if(surface!=0L) vk.deviceExtensionNames(physical) else emptyList()
        val wait=surface!=0L && VK_KHR_PRESENT_ID_EXTENSION_NAME in offered && VK_KHR_PRESENT_WAIT_EXTENSION_NAME in offered &&
            System.getenv("SILLAGE_PRESENT_WAIT")!="0"
        // With the present wait if the driver offers it; a driver that refuses its features gets a device without.
        if(wait) try { createDevice(true); return } catch(e: RuntimeException) { System.err.println("Sillage: no present wait (" + e.message + ")") }
        createDevice(false)
    }

    private fun createDevice(wait: Boolean) = stack.frame { s ->
        val queues=s.struct(VkDeviceQueueCreateInfo, "queueFamilyIndex" to family, "queueCount" to 1, "pQueuePriorities" to s.floats(1f))
        val ci=s.struct(VkDeviceCreateInfo, "queueCreateInfoCount" to 1, "pQueueCreateInfos" to queues)
        if(surface != 0L) {
            val names=if(wait) listOf(VK_KHR_SWAPCHAIN_EXTENSION_NAME,VK_KHR_PRESENT_ID_EXTENSION_NAME,VK_KHR_PRESENT_WAIT_EXTENSION_NAME) else listOf(VK_KHR_SWAPCHAIN_EXTENSION_NAME)
            ci.put(VkDeviceCreateInfo, "enabledExtensionCount" to names.size, "ppEnabledExtensionNames" to s.strings(names))
        }
        if(wait) {
            val waitFeatures=s.struct(VkPhysicalDevicePresentWaitFeaturesKHR, "presentWait" to true)
            val idFeatures=s.struct(VkPhysicalDevicePresentIdFeaturesKHR, "pNext" to waitFeatures, "presentId" to true)
            ci.put(VkDeviceCreateInfo, "pNext" to idFeatures)
        }
        val p=s.allocate(ADDRESS); vk.createDevice(physical,ci,p); dev=VkDeviceApi(p.get(ADDRESS,0),vk,surface != 0L,wait)
        attenteAffichage=wait
        dev.getDeviceQueue(family,0,p); queue=p.get(ADDRESS,0)
    }

    private fun memoryType(bits: Int, flags: Int): Int =
        memoryFlags.indices.firstOrNull { bits and (1 shl it) != 0 && memoryFlags[it] and flags == flags }
            ?: error("No Vulkan memory type with flags=" + flags)

    private fun buffer(size: Long, usage: Int): Buffer = stack.frame { s ->
        val p=s.allocate(JAVA_LONG)
        dev.createBuffer(s.struct(VkBufferCreateInfo, "size" to size, "usage" to usage, "sharingMode" to VK_SHARING_MODE_EXCLUSIVE),p)
        val handle=p.get(JAVA_LONG,0)
        val req=s.struct(VkMemoryRequirements); dev.getBufferMemoryRequirements(handle,req)
        dev.allocateMemory(s.struct(VkMemoryAllocateInfo, "allocationSize" to req.u64(VkMemoryRequirements,"size"),
            "memoryTypeIndex" to memoryType(req.u32(VkMemoryRequirements,"memoryTypeBits"),
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)),p)
        val memory=p.get(JAVA_LONG,0)
        dev.bindBufferMemory(handle,memory,0)
        Buffer(handle,memory,size)
    }

    private fun upload(b: Buffer, vertices: FloatArray) = stack.frame { s ->
        require(vertices.size.toLong()*4<=b.size)
        val p=s.allocate(ADDRESS); dev.mapMemory(b.memory,0,b.size,p)
        try { MemorySegment.copy(vertices,0,p.get(ADDRESS,0).reinterpret(b.size),JAVA_FLOAT,0,vertices.size) }
        finally { dev.unmapMemory(b.memory) }
    }

    private fun image(format: Int, usage: Int, aspect: Int, samples: Int = 1, width: Int = largeur, height: Int = hauteur, layers: Int = 1): Image = stack.frame { s ->
        val p=s.allocate(JAVA_LONG)
        dev.createImage(s.struct(VkImageCreateInfo, "imageType" to VK_IMAGE_TYPE_2D, "format" to format,
            "extent.width" to width, "extent.height" to height, "extent.depth" to 1, "mipLevels" to 1, "arrayLayers" to layers,
            "samples" to samples, "tiling" to VK_IMAGE_TILING_OPTIMAL, "usage" to usage,
            "sharingMode" to VK_SHARING_MODE_EXCLUSIVE, "initialLayout" to VK_IMAGE_LAYOUT_UNDEFINED),p)
        val img=p.get(JAVA_LONG,0)
        val req=s.struct(VkMemoryRequirements); dev.getImageMemoryRequirements(img,req)
        dev.allocateMemory(s.struct(VkMemoryAllocateInfo, "allocationSize" to req.u64(VkMemoryRequirements,"size"),
            "memoryTypeIndex" to memoryType(req.u32(VkMemoryRequirements,"memoryTypeBits"),VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)),p)
        val mem=p.get(JAVA_LONG,0)
        dev.bindImageMemory(img,mem,0)
        dev.createImageView(s.struct(VkImageViewCreateInfo, "image" to img, "viewType" to (if(layers>1) VK_IMAGE_VIEW_TYPE_2D_ARRAY else VK_IMAGE_VIEW_TYPE_2D),
            "format" to format, "subresourceRange.aspectMask" to aspect, "subresourceRange.levelCount" to 1, "subresourceRange.layerCount" to layers),p)
        Image(img,mem,p.get(JAVA_LONG,0))
    }

    /** Maps a host buffer for good. */
    private fun map(b: Buffer): MemorySegment = stack.frame { s ->
        val p=s.allocate(ADDRESS); dev.mapMemory(b.memory,0,b.size,p); p.get(ADDRESS,0).reinterpret(b.size)
    }

    /** The zeros read as the effects of meshes without any, enough for [vertices] vertices. */
    private fun growZero(vertices: Int) {
        zero?.let { dev.deviceWaitIdle(); destroy(it) }
        val b=buffer(Integer.highestOneBit(maxOf(vertices,1024)-1)*2L*4*EFFECT_FLOATS,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
        stack.frame { s -> val p=s.allocate(ADDRESS); dev.mapMemory(b.memory,0,b.size,p)
            try { p.get(ADDRESS,0).reinterpret(b.size).fill(0) } finally { dev.unmapMemory(b.memory) } }
        zero=b
    }

    private fun attachment(a: MemorySegment, i: Int, format: Int, samples: Int, load: Int, store: Int, finalLayout: Int) {
        a.at(VkAttachmentDescription,i).put(VkAttachmentDescription, "format" to format, "samples" to samples,
            "loadOp" to load, "storeOp" to store,
            "stencilLoadOp" to VK_ATTACHMENT_LOAD_OP_DONT_CARE, "stencilStoreOp" to VK_ATTACHMENT_STORE_OP_DONT_CARE,
            "initialLayout" to VK_IMAGE_LAYOUT_UNDEFINED, "finalLayout" to finalLayout)
    }

    private fun dependency(d: MemorySegment, i: Int, src: Int, dst: Int, srcStage: Int, dstStage: Int, srcAccess: Int, dstAccess: Int) {
        d.at(VkSubpassDependency,i).put(VkSubpassDependency, "srcSubpass" to src, "dstSubpass" to dst,
            "srcStageMask" to srcStage, "dstStageMask" to dstStage, "srcAccessMask" to srcAccess, "dstAccessMask" to dstAccess)
    }

    /** A render pass of one subpass: [count] attachments, colour 0 (if [colour]), depth at [depthAt] (−1: none), resolve at [resolveAt]. */
    private fun renderPass(s: NativeStack, a: MemorySegment, count: Int, colour: Boolean, depthAt: Int, resolveAt: Int, deps: MemorySegment): Long {
        val sub=s.struct(VkSubpassDescription, "pipelineBindPoint" to VK_PIPELINE_BIND_POINT_GRAPHICS)
        if(colour) sub.put(VkSubpassDescription, "colorAttachmentCount" to 1,
            "pColorAttachments" to s.struct(VkAttachmentReference, "attachment" to 0, "layout" to VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL))
        if(depthAt>=0) sub.put(VkSubpassDescription, "pDepthStencilAttachment" to
            s.struct(VkAttachmentReference, "attachment" to depthAt, "layout" to VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL))
        if(resolveAt>=0) sub.put(VkSubpassDescription, "pResolveAttachments" to
            s.struct(VkAttachmentReference, "attachment" to resolveAt, "layout" to VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL))
        val p=s.allocate(JAVA_LONG)
        dev.createRenderPass(s.struct(VkRenderPassCreateInfo, "attachmentCount" to count, "pAttachments" to a,
            "subpassCount" to 1, "pSubpasses" to sub, "dependencyCount" to 2, "pDependencies" to deps),p)
        return p.get(JAVA_LONG,0)
    }

    /**
     * The scene's pass (linear light and depth, multisampled when the GPU allows, resolved into an image the
     * later passes read), the passes that make one image from others, and the finished picture's pass. Each
     * waits for the last frame's reads of what it writes, and leaves its image ready for the next readers.
     */
    private fun createRenderPasses() = stack.frame { s ->
        val multi=echantillons>1
        val read=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
        val colourOut=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT; val fragment=VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
        val write=VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT; val sample=VK_ACCESS_SHADER_READ_BIT
        var a=s.structs(VkAttachmentDescription,3)
        attachment(a,0,hdrFormat,echantillons,VK_ATTACHMENT_LOAD_OP_CLEAR,
            if(multi) VK_ATTACHMENT_STORE_OP_DONT_CARE else VK_ATTACHMENT_STORE_OP_STORE, if(multi) VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL else read)
        attachment(a,1,VK_FORMAT_D32_SFLOAT,echantillons,VK_ATTACHMENT_LOAD_OP_CLEAR,VK_ATTACHMENT_STORE_OP_DONT_CARE,VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
        if(multi) attachment(a,2,hdrFormat,VK_SAMPLE_COUNT_1_BIT,VK_ATTACHMENT_LOAD_OP_DONT_CARE,VK_ATTACHMENT_STORE_OP_STORE,read)
        var d=s.structs(VkSubpassDependency,2)
        dependency(d,0,VK_SUBPASS_EXTERNAL,0,fragment or colourOut or VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
            colourOut or VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT, sample or write or VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
            write or VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
        dependency(d,1,0,VK_SUBPASS_EXTERNAL,colourOut,fragment,write,sample)
        renderPass=renderPass(s,a,if(multi) 3 else 2,true,1,if(multi) 2 else -1,d)
        a=s.structs(VkAttachmentDescription,1)
        attachment(a,0,hdrFormat,VK_SAMPLE_COUNT_1_BIT,VK_ATTACHMENT_LOAD_OP_DONT_CARE,VK_ATTACHMENT_STORE_OP_STORE,read)
        d=s.structs(VkSubpassDependency,2)
        dependency(d,0,VK_SUBPASS_EXTERNAL,0,fragment or colourOut,colourOut,sample or write,write)
        dependency(d,1,0,VK_SUBPASS_EXTERNAL,colourOut,fragment,write,sample)
        postPass=renderPass(s,a,1,true,-1,-1,d)
        a=s.structs(VkAttachmentDescription,1)
        attachment(a,0,colorFormat,VK_SAMPLE_COUNT_1_BIT,VK_ATTACHMENT_LOAD_OP_DONT_CARE,VK_ATTACHMENT_STORE_OP_STORE,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
        d=s.structs(VkSubpassDependency,2)
        // The last frame's copy out of the picture ends before this frame writes it.
        dependency(d,0,VK_SUBPASS_EXTERNAL,0,VK_PIPELINE_STAGE_TRANSFER_BIT or colourOut,colourOut,VK_ACCESS_TRANSFER_READ_BIT or write,write)
        dependency(d,1,0,VK_SUBPASS_EXTERNAL,colourOut,VK_PIPELINE_STAGE_TRANSFER_BIT,write,VK_ACCESS_TRANSFER_READ_BIT)
        compositePass=renderPass(s,a,1,true,-1,-1,d)
    }

    /**
     * The shadow map: a depth image, a render pass that clears and fills it then leaves it ready for
     * sampling, and a comparison sampler (filtered when the GPU can).
     */
    private fun createShadowResources() = stack.frame { s ->
        val fp=s.struct(VkFormatProperties); vk.getPhysicalDeviceFormatProperties(physical,VK_FORMAT_D16_UNORM,fp)
        val features=fp.u32(VkFormatProperties,"optimalTilingFeatures")
        require(features and VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT != 0) { "D16 depth cannot be sampled" }
        val filtered=features and VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT != 0
        val map=image(VK_FORMAT_D16_UNORM,VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT or VK_IMAGE_USAGE_SAMPLED_BIT,VK_IMAGE_ASPECT_DEPTH_BIT,
            1,shadowSize,shadowSize,CASCADES)
        shadowMap=map
        val a=s.structs(VkAttachmentDescription,1)
        attachment(a,0,VK_FORMAT_D16_UNORM,VK_SAMPLE_COUNT_1_BIT,VK_ATTACHMENT_LOAD_OP_CLEAR,VK_ATTACHMENT_STORE_OP_STORE,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
        val d=s.structs(VkSubpassDependency,2)
        // The last frame's reads of the map end before this frame writes it; this frame's writes end before its reads.
        dependency(d,0,VK_SUBPASS_EXTERNAL,0,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT or VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
            VK_ACCESS_SHADER_READ_BIT,VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
        dependency(d,1,0,VK_SUBPASS_EXTERNAL,VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT)
        shadowPass=renderPass(s,a,1,false,0,-1,d)
        val p=s.allocate(JAVA_LONG)
        // One view and one framebuffer per layer: each cascade is drawn into its own.
        shadowLayerViews=LongArray(CASCADES) { c ->
            dev.createImageView(s.struct(VkImageViewCreateInfo, "image" to map.image, "viewType" to VK_IMAGE_VIEW_TYPE_2D, "format" to VK_FORMAT_D16_UNORM,
                "subresourceRange.aspectMask" to VK_IMAGE_ASPECT_DEPTH_BIT, "subresourceRange.levelCount" to 1,
                "subresourceRange.baseArrayLayer" to c, "subresourceRange.layerCount" to 1),p)
            p.get(JAVA_LONG,0)
        }
        shadowFramebuffers=LongArray(CASCADES) { c ->
            dev.createFramebuffer(s.struct(VkFramebufferCreateInfo, "renderPass" to shadowPass, "attachmentCount" to 1,
                "pAttachments" to s.longs(shadowLayerViews[c]), "width" to shadowSize, "height" to shadowSize, "layers" to 1),p)
            p.get(JAVA_LONG,0)
        }
        val filter=if(filtered) VK_FILTER_LINEAR else VK_FILTER_NEAREST
        dev.createSampler(s.struct(VkSamplerCreateInfo, "magFilter" to filter, "minFilter" to filter,
            "mipmapMode" to VK_SAMPLER_MIPMAP_MODE_NEAREST, "addressModeU" to VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
            "addressModeV" to VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, "addressModeW" to VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
            "maxAnisotropy" to 1f, "compareEnable" to true, "compareOp" to VK_COMPARE_OP_LESS_OR_EQUAL,
            "borderColor" to VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE),p)
        sampler=p.get(JAVA_LONG,0)
        dev.createSampler(s.struct(VkSamplerCreateInfo, "magFilter" to VK_FILTER_LINEAR, "minFilter" to VK_FILTER_LINEAR,
            "mipmapMode" to VK_SAMPLER_MIPMAP_MODE_NEAREST, "addressModeU" to VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
            "addressModeV" to VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, "addressModeW" to VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
            "maxAnisotropy" to 1f, "borderColor" to VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE),p)
        linear=p.get(JAVA_LONG,0)
        dev.createSampler(s.struct(VkSamplerCreateInfo, "magFilter" to VK_FILTER_LINEAR, "minFilter" to VK_FILTER_LINEAR,
            "mipmapMode" to VK_SAMPLER_MIPMAP_MODE_LINEAR, "addressModeU" to VK_SAMPLER_ADDRESS_MODE_REPEAT,
            "addressModeV" to VK_SAMPLER_ADDRESS_MODE_REPEAT, "addressModeW" to VK_SAMPLER_ADDRESS_MODE_REPEAT,
            "maxAnisotropy" to 1f, "maxLod" to 16f, "borderColor" to VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE),p)
        repeat=p.get(JAVA_LONG,0)
    }

    /**
     * Descriptor sets. The scene's, one per frame in flight: the shadow map (binding 0), the frame's
     * uniform block (binding 1) and the detail textures (binding 2, written by [syncTextures]). The later passes': two images each (written with the images, see [createTargets]).
     */
    private fun createDescriptors() = stack.frame { s ->
        val p=s.allocate(JAVA_LONG)
        var b=s.structs(VkDescriptorSetLayoutBinding,3)
        b.at(VkDescriptorSetLayoutBinding,0).put(VkDescriptorSetLayoutBinding, "binding" to 0,
            "descriptorType" to VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, "descriptorCount" to 1, "stageFlags" to VK_SHADER_STAGE_FRAGMENT_BIT)
        b.at(VkDescriptorSetLayoutBinding,1).put(VkDescriptorSetLayoutBinding, "binding" to 1,
            "descriptorType" to VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, "descriptorCount" to 1,
            "stageFlags" to (VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT))
        b.at(VkDescriptorSetLayoutBinding,2).put(VkDescriptorSetLayoutBinding, "binding" to 2,
            "descriptorType" to VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, "descriptorCount" to 1, "stageFlags" to VK_SHADER_STAGE_FRAGMENT_BIT)
        dev.createDescriptorSetLayout(s.struct(VkDescriptorSetLayoutCreateInfo, "bindingCount" to 3, "pBindings" to b),p)
        setLayout=p.get(JAVA_LONG,0)
        b=s.structs(VkDescriptorSetLayoutBinding,2)
        for(i in 0..1) b.at(VkDescriptorSetLayoutBinding,i).put(VkDescriptorSetLayoutBinding, "binding" to i,
            "descriptorType" to VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, "descriptorCount" to 1, "stageFlags" to VK_SHADER_STAGE_FRAGMENT_BIT)
        dev.createDescriptorSetLayout(s.struct(VkDescriptorSetLayoutCreateInfo, "bindingCount" to 2, "pBindings" to b),p)
        postSetLayout=p.get(JAVA_LONG,0)
        val sizes=s.structs(VkDescriptorPoolSize,2)
        sizes.at(VkDescriptorPoolSize,0).put(VkDescriptorPoolSize, "type" to VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, "descriptorCount" to 2*FRAMES+4*BLOOM_LEVELS)
        sizes.at(VkDescriptorPoolSize,1).put(VkDescriptorPoolSize, "type" to VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, "descriptorCount" to FRAMES)
        dev.createDescriptorPool(s.struct(VkDescriptorPoolCreateInfo, "maxSets" to FRAMES+2*BLOOM_LEVELS, "poolSizeCount" to 2, "pPoolSizes" to sizes),p)
        descriptorPool=p.get(JAVA_LONG,0)
        val sets=s.allocate(JAVA_LONG,(2*BLOOM_LEVELS).toLong())
        dev.allocateDescriptorSets(s.struct(VkDescriptorSetAllocateInfo, "descriptorPool" to descriptorPool,
            "descriptorSetCount" to FRAMES, "pSetLayouts" to s.longs(*LongArray(FRAMES) { setLayout })),sets)
        for((i,f) in frames.withIndex()) {
            f.set=sets.getAtIndex(JAVA_LONG,i.toLong())
            val w=s.structs(VkWriteDescriptorSet,2)
            w.at(VkWriteDescriptorSet,0).put(VkWriteDescriptorSet, "dstSet" to f.set, "dstBinding" to 0, "descriptorCount" to 1,
                "descriptorType" to VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, "pImageInfo" to s.struct(VkDescriptorImageInfo,
                    "sampler" to sampler, "imageView" to shadowMap!!.view, "imageLayout" to VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL))
            w.at(VkWriteDescriptorSet,1).put(VkWriteDescriptorSet, "dstSet" to f.set, "dstBinding" to 1, "descriptorCount" to 1,
                "descriptorType" to VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, "pBufferInfo" to s.struct(VkDescriptorBufferInfo,
                    "buffer" to f.uniform!!.buffer, "offset" to 0L, "range" to FRAME_BYTES.toLong()))
            dev.updateDescriptorSets(2,w)
        }
        dev.allocateDescriptorSets(s.struct(VkDescriptorSetAllocateInfo, "descriptorPool" to descriptorPool,
            "descriptorSetCount" to 2*BLOOM_LEVELS, "pSetLayouts" to s.longs(*LongArray(2*BLOOM_LEVELS) { postSetLayout })),sets)
        for(i in postSets.indices) postSets[i]=sets.getAtIndex(JAVA_LONG,i.toLong())
    }

    /**
     * Sends the detail textures to the GPU when new ones have been made since the last time (rare: when a
     * scene is built): one array image, a layer per texture with every level, the layers as large as the
     * largest texture (a smaller one is repeated pixel by pixel). Before any texture, a neutral grey layer.
     * Textures are painted in the background: unless [wait], those not painted yet stay neutral for a few
     * frames and everything is sent again once they all are (a window opens sooner; without a window, and
     * for captures, the renderer waits, so that a frame is the same from run to run).
     */
    private fun syncTextures(wait: Boolean) {
        val count=Texture.count()
        if(count==textureCount && texturesPainted) return
        val all=Texture.all()
        val painted=all.all { it.ready }
        if(count==textureCount && !painted && !wait) return
        texturesPainted=wait || painted
        val size=all.maxOfOrNull { it.size } ?: 4
        val levels=31-Integer.numberOfLeadingZeros(size)+1
        val layers=maxOf(1,all.size)
        // The pixels, level after level, every layer of a level side by side (one copy per level).
        var total=0L; for(j in 0 until levels) total+=4L*(size shr j)*(size shr j)*layers
        val staging=buffer(total,VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
        dev.deviceWaitIdle()
        try {
            stack.frame { s ->
                val p=s.allocate(ADDRESS); dev.mapMemory(staging.memory,0,staging.size,p)
                val out=p.get(ADDRESS,0).reinterpret(staging.size)
                var at=0L
                for(j in 0 until levels) {
                    val side=size shr j
                    for(l in 0 until layers) {
                        val t=all.getOrNull(l)?.takeIf { wait || it.ready }
                        if(t==null) { out.asSlice(at,4L*side*side).fill(128.toByte()); at+=4L*side*side; continue }
                        val px=t.pixels
                        // This level's size in the texture: its own level j−shift, or level 0 repeated.
                        val shift=31-Integer.numberOfLeadingZeros(size/t.size)
                        if(j>=shift) {
                            var offset=0; var sz=t.size; repeat(j-shift) { offset+=4*sz*sz; sz/=2 }
                            MemorySegment.copy(px,offset,out,JAVA_BYTE,at,4*side*side)
                        } else {
                            val k=1 shl (shift-j)
                            for(y in 0 until side) for(x in 0 until side) {
                                val src=4*((y/k)*t.size+x/k)
                                MemorySegment.copy(px,src,out,JAVA_BYTE,at+4L*(y*side+x),4)
                            }
                        }
                        at+=4L*side*side
                    }
                }
                dev.unmapMemory(staging.memory)
            }
            textures?.let { destroy(it) }
            val img=stack.frame { s ->
                val q=s.allocate(JAVA_LONG)
                dev.createImage(s.struct(VkImageCreateInfo, "imageType" to VK_IMAGE_TYPE_2D, "format" to VK_FORMAT_R8G8B8A8_UNORM,
                    "extent.width" to size, "extent.height" to size, "extent.depth" to 1, "mipLevels" to levels, "arrayLayers" to layers,
                    "samples" to VK_SAMPLE_COUNT_1_BIT, "tiling" to VK_IMAGE_TILING_OPTIMAL, "usage" to (VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT),
                    "sharingMode" to VK_SHARING_MODE_EXCLUSIVE, "initialLayout" to VK_IMAGE_LAYOUT_UNDEFINED),q)
                val image=q.get(JAVA_LONG,0)
                val req=s.struct(VkMemoryRequirements); dev.getImageMemoryRequirements(image,req)
                dev.allocateMemory(s.struct(VkMemoryAllocateInfo, "allocationSize" to req.u64(VkMemoryRequirements,"size"),
                    "memoryTypeIndex" to memoryType(req.u32(VkMemoryRequirements,"memoryTypeBits"),VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)),q)
                val mem=q.get(JAVA_LONG,0)
                dev.bindImageMemory(image,mem,0)
                dev.createImageView(s.struct(VkImageViewCreateInfo, "image" to image, "viewType" to VK_IMAGE_VIEW_TYPE_2D_ARRAY,
                    "format" to VK_FORMAT_R8G8B8A8_UNORM, "subresourceRange.aspectMask" to VK_IMAGE_ASPECT_COLOR_BIT,
                    "subresourceRange.levelCount" to levels, "subresourceRange.layerCount" to layers),q)
                Image(image,mem,q.get(JAVA_LONG,0))
            }
            textures=img
            stack.frame { s ->
                val cmd=uploads
                dev.resetCommandBuffer(cmd,0)
                dev.beginCommandBuffer(cmd,s.struct(VkCommandBufferBeginInfo, "flags" to VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT))
                fun all(old: Int, new: Int, srcAccess: Int, dstAccess: Int, srcStage: Int, dstStage: Int) {
                    val b=s.struct(VkImageMemoryBarrier, "oldLayout" to old, "newLayout" to new, "srcAccessMask" to srcAccess, "dstAccessMask" to dstAccess,
                        "srcQueueFamilyIndex" to VK_QUEUE_FAMILY_IGNORED, "dstQueueFamilyIndex" to VK_QUEUE_FAMILY_IGNORED, "image" to img.image,
                        "subresourceRange.aspectMask" to VK_IMAGE_ASPECT_COLOR_BIT, "subresourceRange.levelCount" to levels, "subresourceRange.layerCount" to layers)
                    dev.cmdPipelineBarrier(cmd,srcStage,dstStage,0,0,MemorySegment.NULL,0,MemorySegment.NULL,1,b)
                }
                all(VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,0,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT)
                val regions=s.structs(VkBufferImageCopy,levels)
                var at=0L
                for(j in 0 until levels) {
                    val side=size shr j
                    regions.at(VkBufferImageCopy,j).put(VkBufferImageCopy, "bufferOffset" to at,
                        "imageSubresource.aspectMask" to VK_IMAGE_ASPECT_COLOR_BIT, "imageSubresource.mipLevel" to j,
                        "imageSubresource.baseArrayLayer" to 0, "imageSubresource.layerCount" to layers,
                        "imageExtent.width" to side, "imageExtent.height" to side, "imageExtent.depth" to 1)
                    at+=4L*side*side*layers
                }
                dev.cmdCopyBufferToImage(cmd,staging.buffer,img.image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,levels,regions)
                all(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,VK_ACCESS_TRANSFER_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                dev.endCommandBuffer(cmd)
                dev.queueSubmit(queue,1,s.struct(VkSubmitInfo, "commandBufferCount" to 1, "pCommandBuffers" to s.pointers(cmd)),0L)
                dev.queueWaitIdle(queue)
                for(f in frames) {
                    val w=s.struct(VkWriteDescriptorSet, "dstSet" to f.set, "dstBinding" to 2, "descriptorCount" to 1,
                        "descriptorType" to VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, "pImageInfo" to s.struct(VkDescriptorImageInfo,
                            "sampler" to repeat, "imageView" to img.view, "imageLayout" to VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL))
                    dev.updateDescriptorSets(1,w)
                }
            }
            textureCount=all.size
        } finally { destroy(staging) }
    }

    private fun writePostSet(set: Long, first: Image, second: Image) = stack.frame { s ->
        val w=s.structs(VkWriteDescriptorSet,2)
        for((i,img) in listOf(first,second).withIndex()) w.at(VkWriteDescriptorSet,i).put(VkWriteDescriptorSet, "dstSet" to set,
            "dstBinding" to i, "descriptorCount" to 1, "descriptorType" to VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            "pImageInfo" to s.struct(VkDescriptorImageInfo, "sampler" to linear, "imageView" to img.view, "imageLayout" to VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL))
        dev.updateDescriptorSets(2,w)
    }

    private fun levelWidth(i: Int)=maxOf(1,largeur shr (i+1))
    private fun levelHeight(i: Int)=maxOf(1,hauteur shr (i+1))

    private fun framebuffer(s: NativeStack, pass: Long, views: MemorySegment, count: Int, w: Int, h: Int): Long {
        val p=s.allocate(JAVA_LONG)
        dev.createFramebuffer(s.struct(VkFramebufferCreateInfo, "renderPass" to pass, "attachmentCount" to count,
            "pAttachments" to views, "width" to w, "height" to h, "layers" to 1),p)
        return p.get(JAVA_LONG,0)
    }

    private fun destroyTargets() {
        if(framebuffer!=0L) { dev.destroyFramebuffer(framebuffer); framebuffer=0L }
        if(compositeFramebuffer!=0L) { dev.destroyFramebuffer(compositeFramebuffer); compositeFramebuffer=0L }
        for(i in downFramebuffers.indices) if(downFramebuffers[i]!=0L) { dev.destroyFramebuffer(downFramebuffers[i]); downFramebuffers[i]=0L }
        for(i in upFramebuffers.indices) if(upFramebuffers[i]!=0L) { dev.destroyFramebuffer(upFramebuffers[i]); upFramebuffers[i]=0L }
        color?.let { destroy(it) }; hdr?.let { destroy(it) }; msaa?.let { destroy(it) }; depth?.let { destroy(it) }
        color=null; hdr=null; msaa=null; depth=null
        for(i in bloomDown.indices) { bloomDown[i]?.let { destroy(it) }; bloomDown[i]=null }
        for(i in bloomUp.indices) { bloomUp[i]?.let { destroy(it) }; bloomUp[i]=null }
    }

    /** The images of every pass at the current size, their framebuffers and the later passes' sets; made again when the window changes size. */
    private fun createTargets() = stack.frame { s ->
        destroyTargets()
        val colourUse=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
        val c=image(colorFormat,colourUse or VK_IMAGE_USAGE_TRANSFER_SRC_BIT,VK_IMAGE_ASPECT_COLOR_BIT); color=c
        val h=image(hdrFormat,colourUse or VK_IMAGE_USAGE_SAMPLED_BIT,VK_IMAGE_ASPECT_COLOR_BIT); hdr=h
        val m=if(echantillons>1) image(hdrFormat,colourUse,VK_IMAGE_ASPECT_COLOR_BIT,echantillons) else null; msaa=m
        val d=image(VK_FORMAT_D32_SFLOAT,VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,VK_IMAGE_ASPECT_DEPTH_BIT,echantillons); depth=d
        framebuffer=if(m!=null) framebuffer(s,renderPass,s.longs(m.view,d.view,h.view),3,largeur,hauteur)
            else framebuffer(s,renderPass,s.longs(h.view,d.view),2,largeur,hauteur)
        compositeFramebuffer=framebuffer(s,compositePass,s.longs(c.view),1,largeur,hauteur)
        for(i in 0 until BLOOM_LEVELS) {
            val img=image(hdrFormat,colourUse or VK_IMAGE_USAGE_SAMPLED_BIT,VK_IMAGE_ASPECT_COLOR_BIT,1,levelWidth(i),levelHeight(i))
            bloomDown[i]=img; downFramebuffers[i]=framebuffer(s,postPass,s.longs(img.view),1,levelWidth(i),levelHeight(i))
            if(i<BLOOM_LEVELS-1) {
                val up=image(hdrFormat,colourUse or VK_IMAGE_USAGE_SAMPLED_BIT,VK_IMAGE_ASPECT_COLOR_BIT,1,levelWidth(i),levelHeight(i))
                bloomUp[i]=up; upFramebuffers[i]=framebuffer(s,postPass,s.longs(up.view),1,levelWidth(i),levelHeight(i))
            }
        }
        // Down: the scene, then each level from the last. Up: the smaller level and this level's. Last: the scene and the glow.
        writePostSet(postSets[0],h,h)
        for(i in 1 until BLOOM_LEVELS) writePostSet(postSets[i],bloomDown[i-1]!!,bloomDown[i-1]!!)
        for(i in BLOOM_LEVELS-2 downTo 0) writePostSet(postSets[upSet(i)],if(i==BLOOM_LEVELS-2) bloomDown[i+1]!! else bloomUp[i+1]!!,bloomDown[i]!!)
        writePostSet(postSets[2*BLOOM_LEVELS-1],h,bloomUp[0]!!)
    }
    private fun upSet(level: Int)=BLOOM_LEVELS+(BLOOM_LEVELS-2-level)

    private fun shader(resource: String): Long = stack.frame { s ->
        val bytes=javaClass.getResourceAsStream(resource)?.use { it.readAllBytes() } ?: error("Shader missing: " + resource + " (run tools/shaders.sh)")
        val code=s.allocate(bytes.size.toLong(),4); MemorySegment.copy(bytes,0,code,JAVA_BYTE,0,bytes.size)
        val p=s.allocate(JAVA_LONG)
        dev.createShaderModule(s.struct(VkShaderModuleCreateInfo, "codeSize" to bytes.size.toLong(), "pCode" to code),p)
        p.get(JAVA_LONG,0)
    }

    /**
     * Vertex inputs. The scene's meshes: binding 0 the mesh (position, normal, colour), binding 1 one model
     * matrix per copy as three rows, binding 2 per vertex its own light and sway, its texture ([Mesh.effects], or zeros).
     */
    private fun sceneInput(s: NativeStack, shadow: Boolean): MemorySegment {
        val bindings=s.structs(VkVertexInputBindingDescription,3)
        bindings.at(VkVertexInputBindingDescription,0).put(VkVertexInputBindingDescription, "binding" to 0, "stride" to 36, "inputRate" to VK_VERTEX_INPUT_RATE_VERTEX)
        bindings.at(VkVertexInputBindingDescription,1).put(VkVertexInputBindingDescription, "binding" to 1, "stride" to 48, "inputRate" to VK_VERTEX_INPUT_RATE_INSTANCE)
        bindings.at(VkVertexInputBindingDescription,2).put(VkVertexInputBindingDescription, "binding" to 2, "stride" to 4*EFFECT_FLOATS, "inputRate" to VK_VERTEX_INPUT_RATE_VERTEX)
        val locations=if(shadow) intArrayOf(0,3,4,5,6,7,8) else intArrayOf(0,1,2,3,4,5,6,7,8)
        val attrs=s.structs(VkVertexInputAttributeDescription,locations.size)
        for((k,loc) in locations.withIndex()) {
            val (binding,format,offset)=when(loc) {
                in 0..2 -> Triple(0,VK_FORMAT_R32G32B32_SFLOAT,loc*12)
                in 3..5 -> Triple(1,VK_FORMAT_R32G32B32A32_SFLOAT,(loc-3)*16)
                else -> Triple(2,VK_FORMAT_R32G32B32A32_SFLOAT,(loc-6)*16)
            }
            attrs.at(VkVertexInputAttributeDescription,k).put(VkVertexInputAttributeDescription,
                "binding" to binding, "location" to loc, "format" to format, "offset" to offset)
        }
        return s.struct(VkPipelineVertexInputStateCreateInfo, "vertexBindingDescriptionCount" to 3, "pVertexBindingDescriptions" to bindings,
            "vertexAttributeDescriptionCount" to locations.size, "pVertexAttributeDescriptions" to attrs)
    }

    /** The interface: per vertex its place, texture or local coordinates, colour, shape and its parameters (ui/Painter.kt). */
    private fun hudInput(s: NativeStack): MemorySegment {
        val binding=s.struct(VkVertexInputBindingDescription, "binding" to 0, "stride" to 4*HUD_FLOATS, "inputRate" to VK_VERTEX_INPUT_RATE_VERTEX)
        val attrs=s.structs(VkVertexInputAttributeDescription,5)
        val formats=intArrayOf(HUD_FORMAT_RG32F,HUD_FORMAT_RG32F,VK_FORMAT_R32G32B32A32_SFLOAT,VK_FORMAT_R32G32B32A32_SFLOAT,VK_FORMAT_R32G32B32A32_SFLOAT)
        val at=intArrayOf(0,8,16,32,48)
        for(i in 0..4) attrs.at(VkVertexInputAttributeDescription,i).put(VkVertexInputAttributeDescription,
            "binding" to 0, "location" to i, "format" to formats[i], "offset" to at[i])
        return s.struct(VkPipelineVertexInputStateCreateInfo, "vertexBindingDescriptionCount" to 1, "pVertexBindingDescriptions" to binding,
            "vertexAttributeDescriptionCount" to 5, "pVertexAttributeDescriptions" to attrs)
    }

    /** Particles: per instance its centre and size, its colour and opacity. */
    private fun particleInput(s: NativeStack): MemorySegment {
        val binding=s.struct(VkVertexInputBindingDescription, "binding" to 0, "stride" to PARTICLE_FLOATS*4, "inputRate" to VK_VERTEX_INPUT_RATE_INSTANCE)
        val attrs=s.structs(VkVertexInputAttributeDescription,2)
        for(i in 0..1) attrs.at(VkVertexInputAttributeDescription,i).put(VkVertexInputAttributeDescription,
            "binding" to 0, "location" to i, "format" to VK_FORMAT_R32G32B32A32_SFLOAT, "offset" to i*16)
        return s.struct(VkPipelineVertexInputStateCreateInfo, "vertexBindingDescriptionCount" to 1, "pVertexBindingDescriptions" to binding,
            "vertexAttributeDescriptionCount" to 2, "pVertexAttributeDescriptions" to attrs)
    }

    /** Debug lines: per vertex its position, its colour and opacity. */
    private fun lineInput(s: NativeStack): MemorySegment {
        val binding=s.struct(VkVertexInputBindingDescription, "binding" to 0, "stride" to LINE_FLOATS*4, "inputRate" to VK_VERTEX_INPUT_RATE_VERTEX)
        val attrs=s.structs(VkVertexInputAttributeDescription,2)
        attrs.at(VkVertexInputAttributeDescription,0).put(VkVertexInputAttributeDescription, "binding" to 0, "location" to 0, "format" to VK_FORMAT_R32G32B32_SFLOAT, "offset" to 0)
        attrs.at(VkVertexInputAttributeDescription,1).put(VkVertexInputAttributeDescription, "binding" to 0, "location" to 1, "format" to VK_FORMAT_R32G32B32A32_SFLOAT, "offset" to 12)
        return s.struct(VkPipelineVertexInputStateCreateInfo, "vertexBindingDescriptionCount" to 1, "pVertexBindingDescriptions" to binding,
            "vertexAttributeDescriptionCount" to 2, "pVertexAttributeDescriptions" to attrs)
    }

    /**
     * One graphics pipeline: triangles (or [topology]), no culling, viewport and scissor set when drawing.
     * [blend]: see BLEND_KEEP_ALPHA; [keepAlpha] leaves the image's alpha (the distance to the eye) untouched.
     */
    private fun make(s: NativeStack, vert: Long, frag: Long?, input: MemorySegment, pass: Long, samples: Int, layout: Long,
                     depthTest: Boolean, depthWrite: Boolean, blend: IntArray?, bias: Boolean = false,
                     topology: Int = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, keepAlpha: Boolean = false): Long {
        val main=s.allocateFrom("main")
        val stages=s.structs(VkPipelineShaderStageCreateInfo,2)
        stages.at(VkPipelineShaderStageCreateInfo,0).put(VkPipelineShaderStageCreateInfo, "stage" to VK_SHADER_STAGE_VERTEX_BIT, "module" to vert, "pName" to main)
        if(frag!=null) stages.at(VkPipelineShaderStageCreateInfo,1).put(VkPipelineShaderStageCreateInfo, "stage" to VK_SHADER_STAGE_FRAGMENT_BIT, "module" to frag, "pName" to main)
        val ia=s.struct(VkPipelineInputAssemblyStateCreateInfo, "topology" to topology)
        val vs=s.struct(VkPipelineViewportStateCreateInfo, "viewportCount" to 1, "scissorCount" to 1)
        val dyn=s.struct(VkPipelineDynamicStateCreateInfo, "dynamicStateCount" to 2, "pDynamicStates" to s.ints(VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR))
        val rs=s.struct(VkPipelineRasterizationStateCreateInfo, "polygonMode" to VK_POLYGON_MODE_FILL, "cullMode" to VK_CULL_MODE_NONE,
            "frontFace" to VK_FRONT_FACE_COUNTER_CLOCKWISE, "lineWidth" to 1f)
        // The shadow map: a slope-scaled bias keeps lit faces from shadowing themselves.
        if(bias) rs.put(VkPipelineRasterizationStateCreateInfo, "depthBiasEnable" to true, "depthBiasConstantFactor" to 1.5f, "depthBiasSlopeFactor" to 2f)
        val ms=s.struct(VkPipelineMultisampleStateCreateInfo, "rasterizationSamples" to samples)
        val ds=s.struct(VkPipelineDepthStencilStateCreateInfo, "depthTestEnable" to depthTest, "depthWriteEnable" to depthWrite,
            "depthCompareOp" to VK_COMPARE_OP_LESS_OR_EQUAL)
        val ca=s.struct(VkPipelineColorBlendAttachmentState, "colorWriteMask" to
            (VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or (if(keepAlpha) 0 else VK_COLOR_COMPONENT_A_BIT)))
        if(blend!=null) ca.put(VkPipelineColorBlendAttachmentState, "blendEnable" to true,
            "srcColorBlendFactor" to blend[0], "dstColorBlendFactor" to blend[1], "colorBlendOp" to VK_BLEND_OP_ADD,
            "srcAlphaBlendFactor" to blend[2], "dstAlphaBlendFactor" to blend[3], "alphaBlendOp" to VK_BLEND_OP_ADD)
        val cb=s.struct(VkPipelineColorBlendStateCreateInfo, "attachmentCount" to (if(frag!=null) 1 else 0), "pAttachments" to ca)
        val p=s.allocate(JAVA_LONG)
        dev.createGraphicsPipelines(1,s.struct(VkGraphicsPipelineCreateInfo, "stageCount" to (if(frag!=null) 2 else 1), "pStages" to stages,
            "pVertexInputState" to input, "pInputAssemblyState" to ia, "pViewportState" to vs, "pRasterizationState" to rs,
            "pMultisampleState" to ms, "pDepthStencilState" to ds, "pColorBlendState" to cb, "pDynamicState" to dyn, "layout" to layout,
            "renderPass" to pass, "subpass" to 0, "basePipelineIndex" to -1),p)
        return p.get(JAVA_LONG,0)
    }

    private fun createPipelines() = stack.frame { s ->
        val modules=HashMap<String,Long>()
        fun m(name: String)=modules.getOrPut(name) { shader("/shaders/$name.spv") }
        try {
            val p=s.allocate(JAVA_LONG)
            val push=s.struct(VkPushConstantRange, "stageFlags" to (VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT), "offset" to 0, "size" to PUSH_BYTES)
            dev.createPipelineLayout(s.struct(VkPipelineLayoutCreateInfo, "setLayoutCount" to 1, "pSetLayouts" to s.longs(setLayout),
                "pushConstantRangeCount" to 1, "pPushConstantRanges" to push),p)
            layout=p.get(JAVA_LONG,0)
            val postPush=s.struct(VkPushConstantRange, "stageFlags" to VK_SHADER_STAGE_FRAGMENT_BIT, "offset" to 0, "size" to 32)
            dev.createPipelineLayout(s.struct(VkPipelineLayoutCreateInfo, "setLayoutCount" to 1, "pSetLayouts" to s.longs(postSetLayout),
                "pushConstantRangeCount" to 1, "pPushConstantRanges" to postPush),p)
            postLayout=p.get(JAVA_LONG,0)
            val scene=sceneInput(s,false)
            val none=s.struct(VkPipelineVertexInputStateCreateInfo)
            val n=echantillons
            pipeline=make(s,m("toon.vert"),m("toon.frag"),scene,renderPass,n,layout,true,true,null)
            pipelineWater=make(s,m("toon.vert"),m("water.frag"),scene,renderPass,n,layout,true,true,null)
            pipelineSky=make(s,m("toon.vert"),m("sky.frag"),scene,renderPass,n,layout,true,false,null)
            pipelineOmbre=make(s,m("toon.vert"),m("ombre.frag"),scene,renderPass,n,layout,true,false,BLEND_KEEP_ALPHA)
            pipelineParticles=make(s,m("particle.vert"),m("particle.frag"),particleInput(s),renderPass,n,layout,true,false,BLEND_PREMULTIPLIED)
            pipelineLinesXray=make(s,m("lines.vert"),m("lines_xray.frag"),lineInput(s),renderPass,n,layout,false,false,BLEND_KEEP_ALPHA,
                topology=VK_PRIMITIVE_TOPOLOGY_LINE_LIST,keepAlpha=true)
            pipelineLines=make(s,m("lines.vert"),m("lines.frag"),lineInput(s),renderPass,n,layout,true,false,BLEND_KEEP_ALPHA,
                topology=VK_PRIMITIVE_TOPOLOGY_LINE_LIST,keepAlpha=true)
            shadowPipeline=make(s,m("shadow.vert"),null,sceneInput(s,true),shadowPass,VK_SAMPLE_COUNT_1_BIT,layout,true,true,null,bias=true)
            shadowPipelineCut=make(s,m("shadow.vert"),m("shadow.frag"),sceneInput(s,true),shadowPass,VK_SAMPLE_COUNT_1_BIT,layout,true,true,null,bias=true)
            pipelineBright=make(s,m("post.vert"),m("bright.frag"),none,postPass,1,postLayout,false,false,null)
            pipelineDown=make(s,m("post.vert"),m("down.frag"),none,postPass,1,postLayout,false,false,null)
            pipelineUp=make(s,m("post.vert"),m("up.frag"),none,postPass,1,postLayout,false,false,null)
            pipelineComposite=make(s,m("post.vert"),m("composite.frag"),none,compositePass,1,postLayout,false,false,null)
            pipelineHud=make(s,m("hud.vert"),m("hud.frag"),hudInput(s),compositePass,1,hudGpu.layout,false,false,BLEND_KEEP_ALPHA)
        } finally { modules.values.forEach { dev.destroyShaderModule(it) } }
    }

    fun recreateSwapchain(): Boolean {
        val source=target ?: return true
        if(surface == 0L) return true
        return stack.frame { s ->
            val (w,h)=source.framebufferSize().let { it[0] to it[1] }
            if(w==0 || h==0) return@frame false
            dev.deviceWaitIdle()
            val caps=s.struct(VkSurfaceCapabilitiesKHR); vk.getPhysicalDeviceSurfaceCapabilitiesKHR(physical,surface,caps)
            fun cap(field: String)=caps.u32(VkSurfaceCapabilitiesKHR,field)
            require(cap("supportedUsageFlags") and VK_IMAGE_USAGE_TRANSFER_DST_BIT != 0) { "The surface cannot be a transfer destination" }
            val n=s.allocate(JAVA_INT); vk.getPhysicalDeviceSurfaceFormatsKHR(physical,surface,n,MemorySegment.NULL)
            val formats=s.structs(VkSurfaceFormatKHR,n.get(JAVA_INT,0)); vk.getPhysicalDeviceSurfaceFormatsKHR(physical,surface,n,formats)
            fun format(i: Int)=formats.at(VkSurfaceFormatKHR,i).u32(VkSurfaceFormatKHR,"format")
            val chosen=(0 until n.get(JAVA_INT,0)).firstOrNull { format(it)==VK_FORMAT_B8G8R8A8_UNORM } ?: 0
            val imageFormat=if(format(chosen)==VK_FORMAT_UNDEFINED) VK_FORMAT_B8G8R8A8_UNORM else format(chosen)
            swapWidth=if(cap("currentExtent.width")!=-1) cap("currentExtent.width") else w.coerceIn(cap("minImageExtent.width"),cap("maxImageExtent.width"))
            swapHeight=if(cap("currentExtent.height")!=-1) cap("currentExtent.height") else h.coerceIn(cap("minImageExtent.height"),cap("maxImageExtent.height"))
            // Waiting for the screen keeps one frame queued whatever the count; without it, every image more is a frame more of delay.
            var count=if(attenteAffichage) cap("minImageCount")+1 else maxOf(2,cap("minImageCount"))
            if(cap("maxImageCount")>0) count=minOf(count,cap("maxImageCount"))
            val composite=listOf(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR)
                .first { cap("supportedCompositeAlpha") and it != 0 }
            val ci=s.struct(VkSwapchainCreateInfoKHR, "surface" to surface, "minImageCount" to count, "imageFormat" to imageFormat,
                "imageColorSpace" to formats.at(VkSurfaceFormatKHR,chosen).u32(VkSurfaceFormatKHR,"colorSpace"),
                "imageExtent.width" to swapWidth, "imageExtent.height" to swapHeight, "imageArrayLayers" to 1,
                "imageUsage" to VK_IMAGE_USAGE_TRANSFER_DST_BIT, "imageSharingMode" to VK_SHARING_MODE_EXCLUSIVE,
                "preTransform" to cap("currentTransform"), "compositeAlpha" to composite, "presentMode" to VK_PRESENT_MODE_FIFO_KHR,
                "clipped" to true, "oldSwapchain" to swapchain)
            val p=s.allocate(JAVA_LONG); dev.createSwapchainKHR(ci,p)
            if(swapchain!=0L) dev.destroySwapchainKHR(swapchain)
            presents=0
            swapchain=p.get(JAVA_LONG,0)
            dev.getSwapchainImagesKHR(swapchain,n,MemorySegment.NULL)
            val imgs=s.allocate(JAVA_LONG,n.get(JAVA_INT,0).toLong()); dev.getSwapchainImagesKHR(swapchain,n,imgs)
            swapImages=LongArray(n.get(JAVA_INT,0)) { imgs.getAtIndex(JAVA_LONG,it.toLong()) }
            if(diagnostic) {
                val pm=s.allocate(JAVA_INT); vk.getPhysicalDevicePresentModesKHR(physical,surface,pm,MemorySegment.NULL)
                val modes=s.allocate(JAVA_INT,pm.get(JAVA_INT,0).toLong()); vk.getPhysicalDevicePresentModesKHR(physical,surface,pm,modes)
                println("SILLAGE_LATENCE swapchain images=${swapImages.size} (min ${cap("minImageCount")}) present modes ${(0 until pm.get(JAVA_INT,0)).map { modes.getAtIndex(JAVA_INT,it.toLong()) }} FIFO, waiting for the screen: $attenteAffichage")
            }
            finished.forEach { dev.destroySemaphore(it) }
            finished=LongArray(swapImages.size) { dev.createSemaphore(s.struct(VkSemaphoreCreateInfo),p); p.get(JAVA_LONG,0) }
            if(swapWidth!=largeur || swapHeight!=hauteur || framebuffer==0L) { largeur=swapWidth; hauteur=swapHeight; createTargets() }
            true
        }
    }

    private fun barrier(command: MemorySegment, image: Long, old: Int, new: Int, srcAccess: Int, dstAccess: Int, srcStage: Int, dstStage: Int, s: NativeStack) {
        val b=s.struct(VkImageMemoryBarrier, "oldLayout" to old, "newLayout" to new, "srcAccessMask" to srcAccess, "dstAccessMask" to dstAccess,
            "srcQueueFamilyIndex" to VK_QUEUE_FAMILY_IGNORED, "dstQueueFamilyIndex" to VK_QUEUE_FAMILY_IGNORED, "image" to image,
            "subresourceRange.aspectMask" to VK_IMAGE_ASPECT_COLOR_BIT, "subresourceRange.levelCount" to 1, "subresourceRange.layerCount" to 1)
        dev.cmdPipelineBarrier(command,srcStage,dstStage,0,0,MemorySegment.NULL,0,MemorySegment.NULL,1,b)
    }

    private fun MemorySegment.subresource(prefix: String, s: Struct) = put(s,
        "$prefix.aspectMask" to VK_IMAGE_ASPECT_COLOR_BIT, "$prefix.mipLevel" to 0, "$prefix.baseArrayLayer" to 0, "$prefix.layerCount" to 1)

    /** Frees the GPU copies of every mesh (when the game leaves a scene for another). */
    fun oublier() {
        dev.deviceWaitIdle()
        meshes.values.forEach { destroy(it) }; meshes.clear()
        effectBuffers.values.forEach { destroy(it) }; effectBuffers.clear()
        dynamicMeshes.values.forEach { d -> d.buffers.forEach { destroy(it) } }; dynamicMeshes.clear()
        batches.clear(); quietBatches.clear(); skyBatches.clear(); shadowBatches.clear(); waterBatches.clear(); for(m in casterBatches) m.clear()
        token=Any()                  // what meshes remember of this renderer is forgotten with it
        cascadeValid.fill(false)     // the next scene draws every shadow map at its first frame
        for(f in frames) f.hudSent=null
    }

    private fun growInstances(f: Frame, floats: Int) {
        f.instances?.let { dev.unmapMemory(it.memory); destroy(it) }
        val b=buffer(floats*4L,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
        f.instances=b
        f.mapped=stack.frame { s -> val p=s.allocate(ADDRESS); dev.mapMemory(b.memory,0,b.size,p); p.get(ADDRESS,0).reinterpret(b.size) }
    }

    /** Adds one copy of [mesh] with the model rows of [node] (times [pose] for a part of an animated model). */
    /**
     * Each mesh keeps its batches for this renderer ([Mesh.batches], valid while [Mesh.batchOwner] is
     * [token]): a world of many copies looks them up without hashing.
     */
    private var token=Any()
    private val maps: Array<IdentityHashMap<Mesh,Batch>> by lazy { arrayOf(batches,quietBatches,skyBatches,shadowBatches,waterBatches,*casterBatches) }

    private fun add(map: IdentityHashMap<Mesh,Batch>, order: ArrayList<Batch>, mesh: Mesh, node: Node, pose: Mat4?) {
        if(mesh.vertices.isEmpty()) return
        var slots=mesh.batches
        if(mesh.batchOwner!==token || slots==null) { slots=arrayOfNulls(5+CASCADES); mesh.batches=slots; mesh.batchOwner=token }
        var slot=0
        while(maps[slot]!==map) slot++
        val b=(slots[slot] as Batch?) ?: map.getOrPut(mesh) { Batch(mesh) }.also { slots[slot]=it }
        if(b.count==0) order.add(b)
        if(b.rows.size<12*(b.count+1)) b.rows=b.rows.copyOf(b.rows.size*2)
        val r=b.rows; val o=12*b.count
        // Node rows: rotation × scale, then the translation (no Mat4 is made).
        val q=node.rotation; val k=node.scale; val p=node.position
        val x=q.x; val y=q.y; val z=q.z; val w=q.w
        val a00=(1-2*(y*y+z*z))*k; val a01=2*(x*y-z*w)*k; val a02=2*(x*z+y*w)*k
        val a10=2*(x*y+z*w)*k; val a11=(1-2*(x*x+z*z))*k; val a12=2*(y*z-x*w)*k
        val a20=2*(x*z-y*w)*k; val a21=2*(y*z+x*w)*k; val a22=(1-2*(x*x+y*y))*k
        if(pose==null) {
            r[o]=a00; r[o+1]=a01; r[o+2]=a02; r[o+3]=p.x
            r[o+4]=a10; r[o+5]=a11; r[o+6]=a12; r[o+7]=p.y
            r[o+8]=a20; r[o+9]=a21; r[o+10]=a22; r[o+11]=p.z
        } else {
            // Mat4 names its coefficients column first (m30 m31 m32: the translation).
            r[o]=a00*pose.m00+a01*pose.m01+a02*pose.m02; r[o+1]=a00*pose.m10+a01*pose.m11+a02*pose.m12
            r[o+2]=a00*pose.m20+a01*pose.m21+a02*pose.m22; r[o+3]=a00*pose.m30+a01*pose.m31+a02*pose.m32+p.x
            r[o+4]=a10*pose.m00+a11*pose.m01+a12*pose.m02; r[o+5]=a10*pose.m10+a11*pose.m11+a12*pose.m12
            r[o+6]=a10*pose.m20+a11*pose.m21+a12*pose.m22; r[o+7]=a10*pose.m30+a11*pose.m31+a12*pose.m32+p.y
            r[o+8]=a20*pose.m00+a21*pose.m01+a22*pose.m02; r[o+9]=a20*pose.m10+a21*pose.m11+a22*pose.m12
            r[o+10]=a20*pose.m20+a21*pose.m21+a22*pose.m22; r[o+11]=a20*pose.m30+a21*pose.m31+a22*pose.m32+p.z
        }
        b.count++
    }

    /** Sorts this frame's copies into batches, one per mesh (new meshes go to the GPU once). Apart from [dessiner], so the JIT compiles it on its own. */
    /** The frame's shadow squares: centre x, y, z and half side per cascade. */
    private val squares=FloatArray(4*CASCADES)
    /**
     * The farther squares are drawn again less often: the finest every frame, the second every two
     * frames, the third every four (at most two shadow passes per frame). A square not drawn again keeps
     * the place it was drawn at ([drawnSquares]), so that the shaders read its map where it really is.
     */
    private val drawnSquares=FloatArray(4*CASCADES)
    private val cascadeValid=BooleanArray(CASCADES)
    private val cascadeNow=BooleanArray(CASCADES)
    private var cascadeFrames=0L

    /** Frustum planes of the frame (a, b, c, d each, normalised; inside: a·x + b·y + c·z + d ≥ 0). */
    private val planes=FloatArray(24)
    /** Whether what the camera cannot see is left out (true); false draws everything, for comparisons. */
    var cull=true
    /** What culling left out this frame, and kept (counted per copy: a node or a part). */
    var culled=0; private set
    var drawn=0; private set

    private fun extractPlanes(m: Mat4) {
        // Gribb & Hartmann: the rows of view × projection added to and taken from the last one.
        val r0=floatArrayOf(m.m00,m.m10,m.m20,m.m30); val r1=floatArrayOf(m.m01,m.m11,m.m21,m.m31)
        val r2=floatArrayOf(m.m02,m.m12,m.m22,m.m32); val r3=floatArrayOf(m.m03,m.m13,m.m23,m.m33)
        val rows=arrayOf(r0,r1)
        var k=0
        for(r in rows) for(sign in floatArrayOf(1f,-1f)) {
            val a=r3[0]+sign*r[0]; val b=r3[1]+sign*r[1]; val c=r3[2]+sign*r[2]; val d=r3[3]+sign*r[3]
            val l=kotlin.math.sqrt(a*a+b*b+c*c).coerceAtLeast(1e-12f)
            planes[k++]=a/l; planes[k++]=b/l; planes[k++]=c/l; planes[k++]=d/l
        }
        // Vulkan's depth goes from 0 to 1: near is row 2 alone, far is row 3 − row 2.
        for((a0,b0,c0,d0) in listOf(floatArrayOf(r2[0],r2[1],r2[2],r2[3]), floatArrayOf(r3[0]-r2[0],r3[1]-r2[1],r3[2]-r2[2],r3[3]-r2[3])).map { it.toList() }) {
            val l=kotlin.math.sqrt(a0*a0+b0*b0+c0*c0).coerceAtLeast(1e-12f)
            planes[k++]=a0/l; planes[k++]=b0/l; planes[k++]=c0/l; planes[k++]=d0/l
        }
    }

    private fun inFrustum(x: Float, y: Float, z: Float, r: Float): Boolean {
        for(i in 0 until 6) { val o=4*i; if(planes[o]*x+planes[o+1]*y+planes[o+2]*z+planes[o+3] < -r) return false }
        return true
    }

    private fun gather(objets: List<Node>, hidden: Node?) = gather(objets,hidden,null,Vec3.ZERO,Float.POSITIVE_INFINITY,FloatArray(4*CASCADES),Vec3.UP)

    /**
     * The frame's copies, grouped by mesh. With [viewProj], what the camera cannot see (outside its
     * frustum, past [haze] or past its node's draw distance) is left out, and only what stands in a
     * cascade's square ([squares]: centre x, y, z and half side for each; half 0: none; seen along
     * [light]) casts into that cascade's map.
     */
    private fun gather(objets: List<Node>, hidden: Node?, viewProj: Mat4?, eye: Vec3, haze: Float, squares: FloatArray, light: Vec3) {
        fun effects(m: Mesh) {
            val e=m.effects
            if(e!=null) { val eb=buffer(e.size.toLong()*4,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT); upload(eb,e); effectBuffers[m]=eb }
            else if(m.vertices.size/9*4L*EFFECT_FLOATS>zero!!.size) growZero(m.vertices.size/9)
        }
        fun prepare(m: Mesh) {
            if(m.vertices.isEmpty()) return
            if(m.dynamic) {
                val d=dynamicMeshes.getOrPut(m) {
                    effects(m)
                    Dynamic(Array(FRAMES) { buffer(m.vertices.size.toLong()*4,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT) }, IntArray(FRAMES) { -1 })
                }
                val v=m.version
                if(d.versions[frameIndex]!=v) { upload(d.buffers[frameIndex],m.vertices); d.versions[frameIndex]=v }
                return
            }
            if(m.preparedBy===token) return
            if(meshes.containsKey(m)) { m.preparedBy=token; return }
            val b=buffer(m.vertices.size.toLong()*4,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
            upload(b,m.vertices); meshes[m]=b; m.preparedBy=token
            val e=m.effects
            if(e!=null) { val eb=buffer(e.size.toLong()*4,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT); upload(eb,e); effectBuffers[m]=eb }
            else if(m.vertices.size/9*4L*EFFECT_FLOATS>zero!!.size) growZero(m.vertices.size/9)
        }
        for(list in casters) for(b in list) b.count=0
        for(b in opaque) b.count=0
        for(b in quiet) b.count=0
        for(b in skies) b.count=0
        for(b in shadows) b.count=0
        for(b in waters) b.count=0
        for(list in casters) list.clear()
        opaque.clear(); quiet.clear(); skies.clear(); shadows.clear(); waters.clear()
        culled=0; drawn=0
        if(viewProj!=null) extractPlanes(viewProj)
        // The shadow square's axes, as light.glsl lays them.
        val ref=if(kotlin.math.abs(light.y)>.99f) Vec3.Z else Vec3.Y
        val sRight=(ref cross light).normalized(); val sUp=light cross sRight
        /** Whether a copy of [mesh] placed by node [o] and [pose] is seen, then whether it casts into the map. */
        var seen=true; var casting=0                                     // casting: one bit per cascade
        fun place(o: Node, wx: Float, wy: Float, wz: Float, r: Float) {
            val dx=wx-eye.x; val dy=wy-eye.y; val dz=wz-eye.z
            val dist=kotlin.math.sqrt(dx*dx+dy*dy+dz*dz)-r
            val near=dist<=haze && dist<=o.drawDistance
            seen=near && inFrustum(wx,wy,wz,r)
            casting=0
            if(!near) return
            for(c in 0 until CASCADES) {
                val half=squares[4*c+3]
                if(half<=0f || !cascadeNow[c]) continue
                val ux=wx-squares[4*c]; val uy=wy-squares[4*c+1]; val uz=wz-squares[4*c+2]
                if(kotlin.math.abs(ux*sRight.x+uy*sRight.y+uz*sRight.z)<=half+r && kotlin.math.abs(ux*sUp.x+uy*sUp.y+uz*sUp.z)<=half+r) casting=casting or (1 shl c)
            }
        }
        fun test(mesh: Mesh, o: Node, pose: Mat4?) {
            if(viewProj==null) { seen=true; casting=(1 shl CASCADES)-1; return }
            if(pose==null) {
                // A node that has not moved since the last frame keeps its world sphere (its place and mesh are immutable values).
                val c=o.cullCache
                if(o.cullPosition!==o.position || o.cullRotation!==o.rotation || o.cullScale!=o.scale || o.cullMesh!==mesh) {
                    val sp=mesh.sphere
                    val q=o.rotation; val qx=q.x; val qy=q.y; val qz=q.z; val qw=q.w
                    val cx=sp[0]; val cy=sp[1]; val cz=sp[2]
                    val tx=2f*(qy*cz-qz*cy); val ty=2f*(qz*cx-qx*cz); val tz=2f*(qx*cy-qy*cx)
                    val k=o.scale; val p=o.position
                    c[0]=(cx+qw*tx+(qy*tz-qz*ty))*k+p.x; c[1]=(cy+qw*ty+(qz*tx-qx*tz))*k+p.y; c[2]=(cz+qw*tz+(qx*ty-qy*tx))*k+p.z
                    c[3]=sp[3]*(if(mesh.dynamic) 1.5f else 1f)*kotlin.math.abs(k)
                    o.cullPosition=o.position; o.cullRotation=o.rotation; o.cullScale=o.scale; o.cullMesh=mesh
                }
                place(o,c[0],c[1],c[2],c[3])
                return
            }
            val sp=mesh.sphere
            var cx=sp[0]; var cy=sp[1]; var cz=sp[2]; var r=sp[3]*(if(mesh.dynamic) 1.5f else 1f)
            if(pose!=null) {
                val px=pose.m00*cx+pose.m10*cy+pose.m20*cz+pose.m30; val py=pose.m01*cx+pose.m11*cy+pose.m21*cz+pose.m31; val pz=pose.m02*cx+pose.m12*cy+pose.m22*cz+pose.m32
                cx=px; cy=py; cz=pz
                val sx=pose.m00*pose.m00+pose.m01*pose.m01+pose.m02*pose.m02; val sy=pose.m10*pose.m10+pose.m11*pose.m11+pose.m12*pose.m12; val sz=pose.m20*pose.m20+pose.m21*pose.m21+pose.m22*pose.m22
                r*=kotlin.math.sqrt(maxOf(sx,sy,sz))
            }
            // The centre turned by the node's rotation (q v q*, written out: nothing allocated), scaled, moved.
            val q=o.rotation; val qx=q.x; val qy=q.y; val qz=q.z; val qw=q.w
            val tx=2f*(qy*cz-qz*cy); val ty=2f*(qz*cx-qx*cz); val tz=2f*(qx*cy-qy*cx)
            val k=o.scale; val p=o.position
            val wx=(cx+qw*tx+(qy*tz-qz*ty))*k+p.x; val wy=(cy+qw*ty+(qz*tx-qx*tz))*k+p.y; val wz=(cz+qw*tz+(qx*ty-qy*tx))*k+p.z
            r*=kotlin.math.abs(k)
            place(o,wx,wy,wz,r)
        }
        for(k in objets.indices) {
            val o=objets[k]
            if(!o.visible || o===hidden) continue
            val mesh=o.meshAt(eye)
            if(o.isSky) { prepare(mesh); add(skyBatches,skies,mesh,o,null); continue }
            if(mesh.vertices.isNotEmpty()) {
                test(mesh,o,null)
                if(seen || casting!=0) prepare(mesh)
                if(o.isShadow) { if(seen) add(shadowBatches,shadows,mesh,o,null) else culled++; continue }
                val look=o.water
                if(look!=null) { if(seen) { add(waterBatches,waters,mesh,o,null); waterBatches[mesh]?.water=look } else culled++; continue }
                val casts=o.castsShadow && !mesh.unlit
                if(seen) { drawn++; add(if(casts) batches else quietBatches,if(casts) opaque else quiet,mesh,o,null) } else culled++
                if(casts && casting!=0) for(c in 0 until CASCADES) if(casting and (1 shl c)!=0) add(casterBatches[c],casters[c],mesh,o,null)
            }
            val parts=o.parts
            val casts=o.castsShadow
            for(j in parts.indices) {
                val p=parts[j]
                test(p.mesh,o,p.pose)
                if(!seen && casting==0) { culled++; continue }
                prepare(p.mesh)
                if(seen) { drawn++; add(if(casts) batches else quietBatches,if(casts) opaque else quiet,p.mesh,o,p.pose) } else culled++
                if(casts && casting!=0) for(c in 0 until CASCADES) if(casting and (1 shl c)!=0) add(casterBatches[c],casters[c],p.mesh,o,p.pose)
            }
        }
    }

    /**
     * The shadow map's centre: [focus] moved to the nearest corner of a map texel, so that the shadows' edges
     * stay still while the camera moves (the basis is the one of light.glsl).
     */
    private fun snapped(focus: Vec3, texel: Float, l: Vec3): Vec3 {
        val ref=if(kotlin.math.abs(l.y)>.99f) Vec3.Z else Vec3.Y
        val right=(ref cross l).normalized(); val up=l cross right
        val u=focus dot right; val v=focus dot up
        return focus + right*(kotlin.math.round(u/texel)*texel-u) + up*(kotlin.math.round(v/texel)*texel-v)
    }

    /**
     * The frame's uniform block (frame.glsl): the light of the hour, the sky, the haze, the wind, the camera's
     * axes (from view × projection: its first row is the camera's right, its last its forward direction) and
     * the point lights.
     */
    private fun writeFrame(f: Frame, l: Lighting, main: Vec3, vp: Mat4, extras: FrameExtras?, squares: FloatArray) {
        val u=f.uniformMapped
        var o=0L
        fun v(x: Float, y: Float, z: Float, w: Float) {
            u.set(JAVA_FLOAT,o,x); u.set(JAVA_FLOAT,o+4,y); u.set(JAVA_FLOAT,o+8,z); u.set(JAVA_FLOAT,o+12,w); o+=16
        }
        fun c(c: Color, w: Float)=v(c.r,c.g,c.b,w)
        val lights=extras?.lightCount ?: 0
        v(main.x,main.y,main.z,l.night)
        c(l.sunColor,l.fogStart)
        c(l.shadeColor,l.fogEnd)
        c(l.skyAmbient,lights.toFloat())
        c(l.groundAmbient,shadowSize.toFloat())
        c(l.horizon,0f); c(l.skyMiddle,0f); c(l.zenith,0f)
        val sun=l.sunDirection.normalized(); v(sun.x,sun.y,sun.z,l.sunShows())
        val moon=l.moonDirection; v(moon.x,moon.y,moon.z,l.moonShows())
        v(l.wind.x,l.wind.z,0f,0f)
        val rl=sqrt(vp.m00*vp.m00+vp.m10*vp.m10+vp.m20*vp.m20).coerceAtLeast(1e-6f)
        val rx=vp.m00/rl; val ry=vp.m10/rl; val rz=vp.m20/rl
        val fl=sqrt(vp.m03*vp.m03+vp.m13*vp.m13+vp.m23*vp.m23).coerceAtLeast(1e-6f)
        val fx=vp.m03/fl; val fy=vp.m13/fl; val fz=vp.m23/fl
        v(rx,ry,rz,0f)
        v(ry*fz-rz*fy,rz*fx-rx*fz,rx*fy-ry*fx,0f)
        c(l.sunGlow,0f)
        for(k in 0 until CASCADES) v(squares[4*k],squares[4*k+1],squares[4*k+2],squares[4*k+3])
        val a=extras?.lights
        for(i in 0 until MAX_LIGHTS) {
            if(a!=null && i<lights) {
                u.set(JAVA_FLOAT,o,a[8*i]); u.set(JAVA_FLOAT,o+4,a[8*i+1]); u.set(JAVA_FLOAT,o+8,a[8*i+2]); u.set(JAVA_FLOAT,o+12,a[8*i+3])
                val q=o+16L*MAX_LIGHTS
                u.set(JAVA_FLOAT,q,a[8*i+4]); u.set(JAVA_FLOAT,q+4,a[8*i+5]); u.set(JAVA_FLOAT,q+8,a[8*i+6]); u.set(JAVA_FLOAT,q+12,0f)
            }
            o+=16
        }
    }

    /**
     * Draws a frame of [objets] (without [hidden], the first-person player) in [lighting]. [camera]:
     * projection × view; [oeil], the camera position, and [temps] (s) go to the shaders. The cast shadows
     * cover a square around [focus] (none when it is null or the scene has no shadows). [extras]: the point
     * lights and particles of the frame.
     */
    /**
     * Waits until the next frame can be drawn, and should be called before reading the keys for it: with a
     * window, until the last frame is on screen (when the driver can tell, see [attenteAffichage]) and a
     * swapchain image is free; then until this frame's resources are free (the GPU done with them, two
     * frames ago). [dessiner] calls it itself if the game did not. False: the window's images had to be
     * made again, skip this frame.
     */
    fun attendre(): Boolean {
        if(pret) return true
        return stack.frame { s ->
            if(attenteAffichage && presents>0) {
                val l=System.nanoTime()
                // A tenth of a second at most: a hidden window may never be shown.
                if(dev.waitForPresentKHR(swapchain,presents,100_000_000L)==VK_SUCCESS && diagnostic) { latShown+=System.nanoTime()-readAt; latShownCount++ }
                latPresentWait+=System.nanoTime()-l
            }
            val f=frames[frameIndex]
            val l0=System.nanoTime()
            dev.waitForFences(1,s.longs(f.fence),Long.MAX_VALUE)
            latFence+=System.nanoTime()-l0
            image=-1
            if(surface!=0L) {
                val out=s.allocate(JAVA_INT)
                val l1=System.nanoTime()
                val result=dev.acquireNextImageKHR(swapchain,Long.MAX_VALUE,f.acquired,VK_NULL_HANDLE,out)
                latAcquire+=System.nanoTime()-l1
                if(result==VK_ERROR_OUT_OF_DATE_KHR) { recreateSwapchain(); return@frame false }
                check(result==VK_SUCCESS || result==VK_SUBOPTIMAL_KHR) { "vkAcquireNextImageKHR: " + result }
                image=out.get(JAVA_INT,0)
            }
            pret=true
            readAt=System.nanoTime()                   // the game reads the keys for this frame now
            true
        }
    }

    fun dessiner(objets: List<Node>, camera: Mat4, hud: HudMesh, capture: Path?=null, oeil: Vec3 = Vec3.ZERO, temps: Float = 0f,
                 hidden: Node? = null, focus: Vec3? = null, lighting: Lighting = defaultLighting, extras: FrameExtras? = null) = stack.frame { s ->
        if(!attendre()) return@frame
        pret=false
        val f=frames[frameIndex]
        val fenceRef=s.longs(f.fence)
        if(f.timed) {
            val t=s.allocate(JAVA_LONG,2)
            if(dev.getQueryPoolResults(queries,2*frameIndex,2,16,t,8,VK_QUERY_RESULT_64_BIT)==VK_SUCCESS) {
                var ticks=t.getAtIndex(JAVA_LONG,1)-t.getAtIndex(JAVA_LONG,0)
                if(timestampBits<64) ticks=ticks and ((1L shl timestampBits)-1)
                gpuMs=ticks*timestampPeriod/1e6
            }
        }
        val index=image
        syncTextures(surface==0L || capture!=null)
        // The main light's squares, finest first: each one's centre (snapped to its texels, so that shadows
        // stay still while the camera moves) and half side; 0 for none.
        val main=lighting.mainLight()
        val on=shadowSize>1 && lighting.shadows && focus!=null
        val n=cascadeFrames++
        for(c in 0 until CASCADES) {
            val half=if(on) lighting.cascadeHalf(c) else 0f
            val due=c==0 || !cascadeValid[c] || (half>0f) != (drawnSquares[4*c+3]>0f) ||
                (c==1 && n%2L==1L) || (c==2 && n%4L==2L)
            cascadeNow[c]=due
            if(due) {
                val ctr=if(half>0f) snapped(focus!!,2f*half/shadowSize,main) else Vec3.ZERO
                drawnSquares[4*c]=ctr.x; drawnSquares[4*c+1]=ctr.y; drawnSquares[4*c+2]=ctr.z; drawnSquares[4*c+3]=half
                cascadeValid[c]=true
            }
            for(k in 0..3) squares[4*c+k]=drawnSquares[4*c+k]
        }
        val range=squares[3]
        val centre=Vec3(squares[0],squares[1],squares[2])
        gather(objets,hidden,if(cull) camera else null,oeil,if(lighting.fogEnd>0f) lighting.fogEnd else Float.POSITIVE_INFINITY,squares,main)
        if(hud!==f.hudSent) { hudGpu.send(f,hud); f.hudSent=hud }
        hudGpu.prepare(hud.atlas)
        // Instance rows of every batch, side by side, then the particles.
        val particleCount=extras?.particleCount ?: 0
        val lineVertices=extras?.lineVertexCount ?: 0
        var total=PARTICLE_FLOATS*particleCount+LINE_FLOATS*lineVertices
        for(list in casters) for(b in list) total+=12*b.count
        for(b in opaque) total+=12*b.count
        for(b in quiet) total+=12*b.count
        for(b in skies) total+=12*b.count
        for(b in shadows) total+=12*b.count
        for(b in waters) total+=12*b.count
        if(total*4L>f.instances!!.size) growInstances(f,Integer.highestOneBit(total)*2)
        val mapped=f.mapped
        var at=0L
        fun put(b: Batch) { b.start=at; MemorySegment.copy(b.rows,0,mapped,JAVA_FLOAT,at,12*b.count); at+=48L*b.count }
        for(list in casters) for(b in list) put(b)
        for(b in opaque) put(b)
        for(b in quiet) put(b)
        for(b in skies) put(b)
        for(b in shadows) put(b)
        for(b in waters) put(b)
        val particleStart=at
        if(particleCount>0) MemorySegment.copy(extras!!.particles,0,mapped,JAVA_FLOAT,at,PARTICLE_FLOATS*particleCount)
        val lineStart=particleStart+4L*PARTICLE_FLOATS*particleCount
        if(lineVertices>0) MemorySegment.copy(extras!!.lines,0,mapped,JAVA_FLOAT,lineStart,LINE_FLOATS*lineVertices)
        writeFrame(f,lighting,main,camera,extras,squares)
        val readback=if(capture!=null) buffer(largeur.toLong()*hauteur*4,VK_BUFFER_USAGE_TRANSFER_DST_BIT) else null
        val command=f.command
        try {
            dev.resetCommandBuffer(command,0)
            dev.beginCommandBuffer(command,s.struct(VkCommandBufferBeginInfo, "flags" to VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT))
            if(queries!=0L) {
                dev.cmdResetQueryPool(command,queries,2*frameIndex,2)
                dev.cmdWriteTimestamp(command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,queries,2*frameIndex)
            }
            hudGpu.record(command)
            val constants=s.allocate(JAVA_FLOAT,32)
            var pushSquare=centre; var pushHalf=range
            fun pushConstants(vp: Mat4, water: WaterLook?=null) {
                // Column by column, as GLSL reads a mat4 (Mat4 names its coefficients column first).
                constants.setAtIndex(JAVA_FLOAT,0,vp.m00); constants.setAtIndex(JAVA_FLOAT,1,vp.m01); constants.setAtIndex(JAVA_FLOAT,2,vp.m02); constants.setAtIndex(JAVA_FLOAT,3,vp.m03)
                constants.setAtIndex(JAVA_FLOAT,4,vp.m10); constants.setAtIndex(JAVA_FLOAT,5,vp.m11); constants.setAtIndex(JAVA_FLOAT,6,vp.m12); constants.setAtIndex(JAVA_FLOAT,7,vp.m13)
                constants.setAtIndex(JAVA_FLOAT,8,vp.m20); constants.setAtIndex(JAVA_FLOAT,9,vp.m21); constants.setAtIndex(JAVA_FLOAT,10,vp.m22); constants.setAtIndex(JAVA_FLOAT,11,vp.m23)
                constants.setAtIndex(JAVA_FLOAT,12,vp.m30); constants.setAtIndex(JAVA_FLOAT,13,vp.m31); constants.setAtIndex(JAVA_FLOAT,14,vp.m32); constants.setAtIndex(JAVA_FLOAT,15,vp.m33)
                constants.setAtIndex(JAVA_FLOAT,16,oeil.x); constants.setAtIndex(JAVA_FLOAT,17,oeil.y); constants.setAtIndex(JAVA_FLOAT,18,oeil.z); constants.setAtIndex(JAVA_FLOAT,19,temps)
                constants.setAtIndex(JAVA_FLOAT,20,pushSquare.x); constants.setAtIndex(JAVA_FLOAT,21,pushSquare.y); constants.setAtIndex(JAVA_FLOAT,22,pushSquare.z); constants.setAtIndex(JAVA_FLOAT,23,pushHalf)
                if(water!=null) {
                    constants.setAtIndex(JAVA_FLOAT,24,water.deep.r); constants.setAtIndex(JAVA_FLOAT,25,water.deep.g); constants.setAtIndex(JAVA_FLOAT,26,water.deep.b)
                    constants.setAtIndex(JAVA_FLOAT,28,water.shallow.r); constants.setAtIndex(JAVA_FLOAT,29,water.shallow.g); constants.setAtIndex(JAVA_FLOAT,30,water.shallow.b)
                    constants.setAtIndex(JAVA_FLOAT,31,water.reach)
                }
                dev.cmdPushConstants(command,layout,VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT,0,PUSH_BYTES,constants)
            }
            val handles=s.allocate(JAVA_LONG,3); val offsets=s.allocate(JAVA_LONG,3)
            handles.setAtIndex(JAVA_LONG,1,f.instances!!.buffer)
            fun draw(b: Batch) {
                val m=b.mesh
                handles.setAtIndex(JAVA_LONG,0,(if(m.dynamic) dynamicMeshes[m]!!.buffers[frameIndex] else meshes[m]!!).buffer)
                handles.setAtIndex(JAVA_LONG,2,(effectBuffers[m] ?: zero!!).buffer)
                offsets.setAtIndex(JAVA_LONG,0,0L); offsets.setAtIndex(JAVA_LONG,1,b.start); offsets.setAtIndex(JAVA_LONG,2,0L)
                dev.cmdBindVertexBuffers(command,0,3,handles,offsets)
                dev.cmdDraw(command,m.vertices.size/9,b.count,0,0)
            }
            fun area(w: Int, h: Int) {
                dev.cmdSetViewport(command,s.struct(VkViewport, "width" to w.toFloat(), "height" to h.toFloat(), "maxDepth" to 1f))
                dev.cmdSetScissor(command,s.struct(VkRect2D, "extent.width" to w, "extent.height" to h))
            }
            val sets=s.longs(f.set)
            // The shadow maps first, one cascade per layer (only cleared when a cascade is off: the shaders then never read it).
            val clearDepth=s.struct(VkClearValue).also { it.put(VkClearDepthStencilValue, "depth" to 1f, "stencil" to 0) }
            for(c in 0 until CASCADES) {
                if(!cascadeNow[c]) continue                  // drawn a frame or three ago: its map is kept
                dev.cmdBeginRenderPass(command,s.struct(VkRenderPassBeginInfo, "renderPass" to shadowPass, "framebuffer" to shadowFramebuffers[c],
                    "renderArea.extent.width" to shadowSize, "renderArea.extent.height" to shadowSize, "clearValueCount" to 1, "pClearValues" to clearDepth),
                    VK_SUBPASS_CONTENTS_INLINE)
                if(squares[4*c+3]>0f && casters[c].isNotEmpty()) {
                    dev.cmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,shadowPipeline)
                    dev.cmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_GRAPHICS,layout,0,1,sets)
                    area(shadowSize,shadowSize)
                    pushSquare=Vec3(squares[4*c],squares[4*c+1],squares[4*c+2]); pushHalf=squares[4*c+3]
                    pushConstants(camera)
                    var cut=false
                    for(b in casters[c]) if(b.mesh.cutOut) cut=true else draw(b)
                    if(cut) {
                        dev.cmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,shadowPipelineCut)
                        for(b in casters[c]) if(b.mesh.cutOut) draw(b)
                    }
                }
                dev.cmdEndRenderPass(command)
            }
            // The scene: the finest square in the push constants (its w > 0 says shadows are on), all of them in the frame block.
            pushSquare=centre; pushHalf=range
            // The scene in linear light; the clear alpha is "very far" for the ink.
            val clear=s.structs(VkClearValue,2)
            clear.at(VkClearValue,0).put(VkClearValue, "float32[0]" to lighting.horizon.r, "float32[1]" to lighting.horizon.g,
                "float32[2]" to lighting.horizon.b, "float32[3]" to 10000f)
            clear.at(VkClearValue,1).put(VkClearDepthStencilValue, "depth" to 1f, "stencil" to 0)
            dev.cmdBeginRenderPass(command,s.struct(VkRenderPassBeginInfo, "renderPass" to renderPass, "framebuffer" to framebuffer,
                "renderArea.extent.width" to largeur, "renderArea.extent.height" to hauteur, "clearValueCount" to 2, "pClearValues" to clear),
                VK_SUBPASS_CONTENTS_INLINE)
            dev.cmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline)
            dev.cmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_GRAPHICS,layout,0,1,sets)
            area(largeur,hauteur)
            pushConstants(camera)
            for(b in opaque) draw(b)
            for(b in quiet) draw(b)
            if(waters.isNotEmpty()) {
                dev.cmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipelineWater)
                for(b in waters) { pushConstants(camera,b.water); draw(b) }
                pushConstants(camera)
            }
            if(skies.isNotEmpty()) {
                dev.cmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipelineSky)
                for(b in skies) draw(b)
            }
            if(shadows.isNotEmpty()) {
                dev.cmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipelineOmbre)
                for(b in shadows) draw(b)
            }
            if(particleCount>0) {
                dev.cmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipelineParticles)
                handles.setAtIndex(JAVA_LONG,2,f.instances!!.buffer); offsets.setAtIndex(JAVA_LONG,2,particleStart)
                dev.cmdBindVertexBuffers(command,0,1,handles.asSlice(16),offsets.asSlice(16))
                dev.cmdDraw(command,6,particleCount,0,0)
            }
            // Debug lines: faint through the scenery first, then plain where they are seen.
            if(lineVertices>0) {
                handles.setAtIndex(JAVA_LONG,2,f.instances!!.buffer); offsets.setAtIndex(JAVA_LONG,2,lineStart)
                dev.cmdBindVertexBuffers(command,0,1,handles.asSlice(16),offsets.asSlice(16))
                dev.cmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipelineLinesXray)
                dev.cmdDraw(command,lineVertices,1,0,0)
                dev.cmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipelineLines)
                dev.cmdDraw(command,lineVertices,1,0,0)
            }
            dev.cmdEndRenderPass(command)
            // The glow: the bright parts at half size, down the levels, then up again, each adding the level below.
            val post=s.allocate(JAVA_FLOAT,8)
            fun pass(pass: Long, fb: Long, w: Int, h: Int, pipe: Long, set: Long, tx: Float, ty: Float, a: Float, b: Float, c: Float, d: Float, z: Float=0f) {
                dev.cmdBeginRenderPass(command,s.struct(VkRenderPassBeginInfo, "renderPass" to pass, "framebuffer" to fb,
                    "renderArea.extent.width" to w, "renderArea.extent.height" to h),VK_SUBPASS_CONTENTS_INLINE)
                dev.cmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipe)
                dev.cmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_GRAPHICS,postLayout,0,1,s.longs(set))
                area(w,h)
                post.setAtIndex(JAVA_FLOAT,0,tx); post.setAtIndex(JAVA_FLOAT,1,ty); post.setAtIndex(JAVA_FLOAT,2,z); post.setAtIndex(JAVA_FLOAT,3,0f)
                post.setAtIndex(JAVA_FLOAT,4,a); post.setAtIndex(JAVA_FLOAT,5,b); post.setAtIndex(JAVA_FLOAT,6,c); post.setAtIndex(JAVA_FLOAT,7,d)
                dev.cmdPushConstants(command,postLayout,VK_SHADER_STAGE_FRAGMENT_BIT,0,32,post)
                dev.cmdDraw(command,3,1,0,0)
            }
            pass(postPass,downFramebuffers[0],levelWidth(0),levelHeight(0),pipelineBright,postSets[0],1f/largeur,1f/hauteur,1f,0f,0f,0f)
            dev.cmdEndRenderPass(command)
            for(i in 1 until BLOOM_LEVELS) {
                pass(postPass,downFramebuffers[i],levelWidth(i),levelHeight(i),pipelineDown,postSets[i],1f/levelWidth(i-1),1f/levelHeight(i-1),0f,0f,0f,0f)
                dev.cmdEndRenderPass(command)
            }
            for(i in BLOOM_LEVELS-2 downTo 0) {
                pass(postPass,upFramebuffers[i],levelWidth(i),levelHeight(i),pipelineUp,postSets[upSet(i)],1f/levelWidth(i+1),1f/levelHeight(i+1),0f,0f,0f,0f)
                dev.cmdEndRenderPass(command)
            }
            // The finished picture, then the interface over it.
            pass(compositePass,compositeFramebuffer,largeur,hauteur,pipelineComposite,postSets[2*BLOOM_LEVELS-1],1f/largeur,1f/hauteur,
                lighting.glow/BLOOM_LEVELS,lighting.outlines,lighting.vignette,lighting.saturation,maxOf(1,Math.round(hauteur/900f)).toFloat())
            if(hud.vertices.isNotEmpty()) {
                dev.cmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipelineHud)
                hudGpu.bind(command,s)
                handles.setAtIndex(JAVA_LONG,2,f.hud!!.buffer); offsets.setAtIndex(JAVA_LONG,2,0L)
                dev.cmdBindVertexBuffers(command,0,1,handles.asSlice(16),offsets.asSlice(16))
                dev.cmdDraw(command,hud.vertices.size/HUD_FLOATS,1,0,0)
            }
            dev.cmdEndRenderPass(command)
            if(readback!=null) {
                val region=s.struct(VkBufferImageCopy, "imageExtent.width" to largeur, "imageExtent.height" to hauteur, "imageExtent.depth" to 1)
                    .subresource("imageSubresource",VkBufferImageCopy)
                dev.cmdCopyImageToBuffer(command,color!!.image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,readback.buffer,1,region)
                val mb=s.struct(VkMemoryBarrier, "srcAccessMask" to VK_ACCESS_TRANSFER_WRITE_BIT, "dstAccessMask" to VK_ACCESS_HOST_READ_BIT)
                dev.cmdPipelineBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,1,mb,0,MemorySegment.NULL,0,MemorySegment.NULL)
            }
            if(index>=0) {
                val img=swapImages[index]
                barrier(command,img,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,0,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,s)
                val blit=s.struct(VkImageBlit, "srcOffsets[1].x" to largeur, "srcOffsets[1].y" to hauteur, "srcOffsets[1].z" to 1,
                    "dstOffsets[1].x" to swapWidth, "dstOffsets[1].y" to swapHeight, "dstOffsets[1].z" to 1)
                    .subresource("srcSubresource",VkImageBlit).subresource("dstSubresource",VkImageBlit)
                dev.cmdBlitImage(command,color!!.image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,img,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,blit,VK_FILTER_NEAREST)
                barrier(command,img,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,VK_ACCESS_TRANSFER_WRITE_BIT,0,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,s)
            }
            if(queries!=0L) dev.cmdWriteTimestamp(command,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,queries,2*frameIndex+1)
            dev.endCommandBuffer(command)
            f.timed=queries!=0L
            val submit=s.struct(VkSubmitInfo, "commandBufferCount" to 1, "pCommandBuffers" to s.pointers(command))
            if(index>=0) submit.put(VkSubmitInfo, "waitSemaphoreCount" to 1, "pWaitSemaphores" to s.longs(f.acquired),
                "pWaitDstStageMask" to s.ints(VK_PIPELINE_STAGE_TRANSFER_BIT), "signalSemaphoreCount" to 1, "pSignalSemaphores" to s.longs(finished[index]))
            dev.resetFences(1,fenceRef)
            dev.queueSubmit(queue,1,submit,f.fence)
            frameIndex=(frameIndex+1)%FRAMES
            if(index>=0) {
                val pi=s.struct(VkPresentInfoKHR, "waitSemaphoreCount" to 1, "pWaitSemaphores" to s.longs(finished[index]),
                    "swapchainCount" to 1, "pSwapchains" to s.longs(swapchain), "pImageIndices" to s.ints(index))
                // Numbered, so that the next frame can wait until this one is on screen.
                if(attenteAffichage) pi.put(VkPresentInfoKHR, "pNext" to s.struct(VkPresentIdKHR, "swapchainCount" to 1, "pPresentIds" to s.longs(++presents)))
                val l2=System.nanoTime()
                val result=dev.queuePresentKHR(queue,pi)
                latPresent+=System.nanoTime()-l2; latFrames++
                if(diagnostic && System.nanoTime()-latSince>1_000_000_000L) {
                    val shown=if(latShownCount>0) "%.1f ms".format(latShown/1e6/latShownCount) else "?"
                    println("SILLAGE_LATENCE keys read → on screen $shown; per frame: waiting for the screen %.2f ms, fence %.2f ms, image %.2f ms, present %.2f ms; %d frames/s"
                        .format(latPresentWait/1e6/latFrames,latFence/1e6/latFrames,latAcquire/1e6/latFrames,latPresent/1e6/latFrames,latFrames))
                    latShown=0; latShownCount=0; latPresentWait=0; latFence=0; latAcquire=0; latPresent=0; latFrames=0; latSince=System.nanoTime()
                }
                if(result==VK_ERROR_OUT_OF_DATE_KHR || result==VK_SUBOPTIMAL_KHR) recreateSwapchain() else result.vkCheck("Present")
            }
            if(capture!=null && readback!=null) { dev.waitForFences(1,fenceRef,Long.MAX_VALUE); save(readback,capture) }
        } finally { if(readback!=null) { dev.waitForFences(1,fenceRef,Long.MAX_VALUE); destroy(readback) } }
    }

    private fun save(b: Buffer, destination: Path) = stack.frame { s ->
        val p=s.allocate(ADDRESS); dev.mapMemory(b.memory,0,b.size,p)
        try {
            val bytes=p.get(ADDRESS,0).reinterpret(b.size)
            // RGBA as read back, RGB for the file (the engine's own PNG writer: no image library).
            val all=bytes.toArray(JAVA_BYTE)
            val rgb=ByteArray(largeur*hauteur*3)
            for(i in 0 until largeur*hauteur) { rgb[3*i]=all[4*i]; rgb[3*i+1]=all[4*i+1]; rgb[3*i+2]=all[4*i+2] }
            sillage.io.PngWriter.write(destination,largeur,hauteur,rgb)
            println("SILLAGE_CAPTURE=" + destination.toAbsolutePath())
        } finally { dev.unmapMemory(b.memory) }
    }
    private fun destroy(b: Buffer) { dev.destroyBuffer(b.buffer); dev.freeMemory(b.memory) }
    private fun destroy(i: Image) { dev.destroyImageView(i.view); dev.destroyImage(i.image); dev.freeMemory(i.memory) }

    override fun close() {
        if(ferme) return
        ferme=true
        try {
            if(::dev.isInitialized) {
                dev.deviceWaitIdle()
                meshes.values.forEach { destroy(it) }; meshes.clear()
                effectBuffers.values.forEach { destroy(it) }; effectBuffers.clear()
                dynamicMeshes.values.forEach { d -> d.buffers.forEach { destroy(it) } }; dynamicMeshes.clear()
                zero?.let { destroy(it) }; zero=null
                hudGpu.close()
                for(f in frames) {
                    f.hud?.let { destroy(it) }; f.instances?.let { dev.unmapMemory(it.memory); destroy(it) }
                    f.uniform?.let { dev.unmapMemory(it.memory); destroy(it) }
                    dev.destroyFence(f.fence); dev.destroySemaphore(f.acquired)
                }
                frames.clear()
                if(queries!=0L) dev.destroyQueryPool(queries)
                for(p in longArrayOf(pipeline,pipelineSky,pipelineOmbre,pipelineWater,pipelineParticles,pipelineLines,pipelineLinesXray,pipelineHud,pipelineBright,
                        pipelineDown,pipelineUp,pipelineComposite,shadowPipeline,shadowPipelineCut)) dev.destroyPipeline(p)
                dev.destroyPipelineLayout(layout); dev.destroyPipelineLayout(postLayout)
                dev.destroyDescriptorPool(descriptorPool); dev.destroyDescriptorSetLayout(setLayout); dev.destroyDescriptorSetLayout(postSetLayout)
                dev.destroySampler(sampler); dev.destroySampler(linear); dev.destroySampler(repeat)
                textures?.let { destroy(it) }; textures=null
                for(fb in shadowFramebuffers) dev.destroyFramebuffer(fb)
                for(v in shadowLayerViews) dev.destroyImageView(v)
                dev.destroyRenderPass(shadowPass)
                shadowMap?.let { destroy(it) }; shadowMap=null
                destroyTargets()
                dev.destroyRenderPass(renderPass); dev.destroyRenderPass(postPass); dev.destroyRenderPass(compositePass)
                dev.destroySwapchainKHR(swapchain)
                finished.forEach { dev.destroySemaphore(it) }
                dev.destroyCommandPool(pool)
                dev.destroyDevice()
            }
            if(::vk.isInitialized) {
                if(surface!=0L) vk.destroySurfaceKHR(surface)
                if(messenger!=0L) vk.destroyDebugUtilsMessengerEXT(messenger)
                vk.destroyInstance()
            }
        } finally { arena.close() }
    }

    // ------------------------------------------------------------------ the interface's glyphs

    /**
     * The HUD's own resources: its pipeline layout (one texture, the atlas's size as push constants), the
     * glyph atlas as an 8-bit image, and per frame in flight a staging buffer. The rows of the atlas that
     * changed are copied at the start of the frame's commands, behind a barrier: new glyphs never stall the
     * GPU. Only when the atlas changes size is the image made again (the device then waits).
     */
    private inner class HudGpu {
        var setLayout=0L; var layout=0L; var pool=0L; var set=0L
        var image: Image?=null; var width=0; var height=0
        var sent: GlyphAtlas?=null; var version=-1; var generation=-1
        val staging=arrayOfNulls<Buffer>(FRAMES); val mapped=arrayOfNulls<MemorySegment>(FRAMES)
        var copyTop=0; var copyRows=0                         // rows to copy this frame from the frame's staging buffer

        fun create() = stack.frame { s ->
            val p=s.allocate(JAVA_LONG)
            val b=s.struct(VkDescriptorSetLayoutBinding, "binding" to 0, "descriptorType" to VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                "descriptorCount" to 1, "stageFlags" to VK_SHADER_STAGE_FRAGMENT_BIT)
            dev.createDescriptorSetLayout(s.struct(VkDescriptorSetLayoutCreateInfo, "bindingCount" to 1, "pBindings" to b),p)
            setLayout=p.get(JAVA_LONG,0)
            val push=s.struct(VkPushConstantRange, "stageFlags" to VK_SHADER_STAGE_FRAGMENT_BIT, "offset" to 0, "size" to 16)
            dev.createPipelineLayout(s.struct(VkPipelineLayoutCreateInfo, "setLayoutCount" to 1, "pSetLayouts" to s.longs(setLayout),
                "pushConstantRangeCount" to 1, "pPushConstantRanges" to push),p)
            layout=p.get(JAVA_LONG,0)
            val size=s.struct(VkDescriptorPoolSize, "type" to VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, "descriptorCount" to 1)
            dev.createDescriptorPool(s.struct(VkDescriptorPoolCreateInfo, "maxSets" to 1, "poolSizeCount" to 1, "pPoolSizes" to size),p)
            pool=p.get(JAVA_LONG,0)
            dev.allocateDescriptorSets(s.struct(VkDescriptorSetAllocateInfo, "descriptorPool" to pool, "descriptorSetCount" to 1,
                "pSetLayouts" to s.longs(setLayout)),p)
            set=p.get(JAVA_LONG,0)
            makeImage(4,4)
        }

        /** A new atlas image of [w] × [h] texels, its texels undefined until the next copy (all rows); the set points at it. */
        fun makeImage(w: Int, h: Int) = stack.frame { s ->
            image?.let { destroy(it) }
            val img=image(HUD_FORMAT_R8,VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT,VK_IMAGE_ASPECT_COLOR_BIT,1,w,h)
            image=img; width=w; height=h
            // Readable at once, so that the set is valid even before the first glyph.
            val cmd=uploads
            dev.resetCommandBuffer(cmd,0)
            dev.beginCommandBuffer(cmd,s.struct(VkCommandBufferBeginInfo, "flags" to VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT))
            barrier(cmd,img.image,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,0,VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,s)
            dev.endCommandBuffer(cmd)
            dev.queueSubmit(queue,1,s.struct(VkSubmitInfo, "commandBufferCount" to 1, "pCommandBuffers" to s.pointers(cmd)),0L)
            dev.queueWaitIdle(queue)
            val w2=s.struct(VkWriteDescriptorSet, "dstSet" to set, "dstBinding" to 0, "descriptorCount" to 1,
                "descriptorType" to VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, "pImageInfo" to s.struct(VkDescriptorImageInfo,
                    "sampler" to linear, "imageView" to img.view, "imageLayout" to VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL))
            dev.updateDescriptorSets(1,w2)
        }

        /** The HUD's vertices into the frame's buffer (made larger if they no longer fit). */
        fun send(f: Frame, hud: HudMesh) {
            val bytes=hud.vertices.size*4L
            if(bytes>f.hud!!.size) { destroy(f.hud!!); f.hud=buffer(maxOf(bytes,2*f.hud!!.size),VK_BUFFER_USAGE_VERTEX_BUFFER_BIT) }
            upload(f.hud!!,hud.vertices)
        }

        /** Before recording: the rows of [atlas] changed since the last frame go to this frame's staging buffer. */
        fun prepare(atlas: GlyphAtlas?) {
            copyRows=0
            if(atlas==null) return
            if(atlas!==sent || atlas.width!=width || atlas.height!=height) {
                dev.deviceWaitIdle()
                makeImage(atlas.width,atlas.height)
                sent=atlas; version=-1
                atlas.takeDirty()
                copy(atlas,0,atlas.height)
            } else if(atlas.version!=version) {
                val (top,bottom)=atlas.takeDirty().let { it[0] to it[1] }
                if(bottom>top) copy(atlas,top,bottom)
            }
            version=atlas.version
        }

        private fun copy(atlas: GlyphAtlas, top: Int, bottom: Int) {
            val bytes=(bottom-top).toLong()*atlas.width
            var b=staging[frameIndex]
            if(b==null || b.size<bytes) {
                b?.let { dev.unmapMemory(it.memory); destroy(it) }
                b=buffer(maxOf(bytes,64L*1024),VK_BUFFER_USAGE_TRANSFER_SRC_BIT); staging[frameIndex]=b; mapped[frameIndex]=map(b)
            }
            MemorySegment.copy(atlas.pixels,top*atlas.width,mapped[frameIndex]!!,JAVA_BYTE,0L,bytes.toInt())
            copyTop=top; copyRows=bottom-top
        }

        /** At the start of the frame's commands: the staged rows into the atlas image. */
        fun record(command: MemorySegment) = stack.frame { s ->
            if(copyRows==0) return@frame
            val img=image!!
            barrier(command,img.image,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,s)
            val region=s.struct(VkBufferImageCopy, "bufferOffset" to 0L, "imageOffset.y" to copyTop,
                "imageExtent.width" to width, "imageExtent.height" to copyRows, "imageExtent.depth" to 1).subresource("imageSubresource",VkBufferImageCopy)
            dev.cmdCopyBufferToImage(command,staging[frameIndex]!!.buffer,img.image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,region)
            barrier(command,img.image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_ACCESS_SHADER_READ_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,s)
        }

        /** Binds the atlas and gives its size to the shader. */
        fun bind(command: MemorySegment, s: NativeStack) {
            dev.cmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_GRAPHICS,layout,0,1,s.longs(set))
            val c=s.allocate(JAVA_FLOAT,4)
            c.setAtIndex(JAVA_FLOAT,0,width.toFloat()); c.setAtIndex(JAVA_FLOAT,1,height.toFloat())
            c.setAtIndex(JAVA_FLOAT,2,1f/width); c.setAtIndex(JAVA_FLOAT,3,1f/height)
            dev.cmdPushConstants(command,layout,VK_SHADER_STAGE_FRAGMENT_BIT,0,16,c)
        }

        fun close() {
            for(i in staging.indices) staging[i]?.let { dev.unmapMemory(it.memory); destroy(it) }
            image?.let { destroy(it) }; image=null
            if(layout!=0L) dev.destroyPipelineLayout(layout)
            if(pool!=0L) dev.destroyDescriptorPool(pool)
            if(setLayout!=0L) dev.destroyDescriptorSetLayout(setLayout)
        }
    }
    private val hudGpu=HudGpu()
}
