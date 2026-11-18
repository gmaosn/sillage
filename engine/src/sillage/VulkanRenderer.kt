package sillage

import sillage.math.Mat4
import sillage.math.Vec3
import sillage.vk.*
import java.awt.image.BufferedImage
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
import javax.imageio.ImageIO

/** What the renderer needs from a window: instance extensions, a VkSurfaceKHR and the drawable size. */
internal interface SurfaceSource {
    val instanceExtensions: List<String>
    fun createSurface(vk: VkInstanceApi, stack: NativeStack): Long
    /** Drawable size in pixels as (width, height); zero while minimised. */
    fun framebufferSize(): IntArray
}

/**
 * Vulkan renderer called through FFM. Two frames in flight: the CPU records the next frame while the GPU
 * draws the last one (each frame has its command buffer, fence and host buffers). Every copy of a mesh is
 * drawn in one call, its model matrices in an instance buffer. The same render pass works with a window
 * or without any display server.
 */
private const val FRAMES = 2
private val IDENTITY_ROWS = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)

internal class VulkanRenderer(private val target: SurfaceSource? = null, private val validation: Boolean = false,
                              largeurHorsEcran: Int = 1280, hauteurHorsEcran: Int = 800,
                              /** Light and haze, given to the toon shader as specialisation constants. */
                              private val lighting: Lighting = Lighting()) : AutoCloseable {
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
    private var memoryFlags=IntArray(0)
    private var pool=0L
    private var renderPass=0L
    private var framebuffer=0L
    private var layout=0L
    private var pipeline=0L
    private var pipelineOmbre=0L
    private class Frame(val command: MemorySegment, val fence: Long, val acquired: Long) {
        var hud: Buffer?=null; var hudSent: Mesh?=null
        var instances: Buffer?=null; var mapped: MemorySegment=MemorySegment.NULL
        var timed=false
    }
    private val frames=ArrayList<Frame>()
    private var frameIndex=0
    private var finished=LongArray(0)          // per swapchain image: presentation waits on it
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
    private var color: Image?=null          // image finale (résolue), copiée vers la fenêtre ou lue pour une capture
    private var msaa: Image?=null           // image multi-échantillonnée où l'on dessine
    private var depth: Image?=null
    private val meshes=IdentityHashMap<Mesh,Buffer>()
    /** Copies of one mesh to draw this frame: their model rows (12 floats each), reused from frame to frame. */
    private class Batch(val mesh: Mesh) { var count=0; var rows=FloatArray(12*8) }
    private val batches=IdentityHashMap<Mesh,Batch>()
    private val shadowBatches=IdentityHashMap<Mesh,Batch>()
    private val opaque=ArrayList<Batch>()
    private val shadows=ArrayList<Batch>()
    private var ferme=false
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
                val cmd=s.allocate(ADDRESS,FRAMES.toLong())
                dev.allocateCommandBuffers(s.struct(VkCommandBufferAllocateInfo,
                    "commandPool" to pool, "level" to VK_COMMAND_BUFFER_LEVEL_PRIMARY, "commandBufferCount" to FRAMES),cmd)
                for(f in 0 until FRAMES) {
                    dev.createFence(s.struct(VkFenceCreateInfo, "flags" to VK_FENCE_CREATE_SIGNALED_BIT),out); val fence=out.get(JAVA_LONG,0)
                    dev.createSemaphore(s.struct(VkSemaphoreCreateInfo),out); val acquired=out.get(JAVA_LONG,0)
                    frames.add(Frame(cmd.getAtIndex(ADDRESS,f.toLong()),fence,acquired).also {
                        it.hud=buffer(4L*1024*1024,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT); growInstances(it,16*1024) })
                }
                if(timestampBits>0 && timestampPeriod>0f) {
                    dev.createQueryPool(s.struct(VkQueryPoolCreateInfo, "queryType" to VK_QUERY_TYPE_TIMESTAMP, "queryCount" to 2*FRAMES),out)
                    queries=out.get(JAVA_LONG,0)
                }
            }
            createRenderPass()
            createPipeline()
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

    private fun createDevice() = stack.frame { s ->
        val queues=s.struct(VkDeviceQueueCreateInfo, "queueFamilyIndex" to family, "queueCount" to 1, "pQueuePriorities" to s.floats(1f))
        val ci=s.struct(VkDeviceCreateInfo, "queueCreateInfoCount" to 1, "pQueueCreateInfos" to queues)
        if(surface != 0L) ci.put(VkDeviceCreateInfo,
            "enabledExtensionCount" to 1, "ppEnabledExtensionNames" to s.strings(listOf(VK_KHR_SWAPCHAIN_EXTENSION_NAME)))
        val p=s.allocate(ADDRESS); vk.createDevice(physical,ci,p); dev=VkDeviceApi(p.get(ADDRESS,0),vk,surface != 0L)
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

    private fun image(format: Int, usage: Int, aspect: Int, samples: Int = 1): Image = stack.frame { s ->
        val p=s.allocate(JAVA_LONG)
        dev.createImage(s.struct(VkImageCreateInfo, "imageType" to VK_IMAGE_TYPE_2D, "format" to format,
            "extent.width" to largeur, "extent.height" to hauteur, "extent.depth" to 1, "mipLevels" to 1, "arrayLayers" to 1,
            "samples" to samples, "tiling" to VK_IMAGE_TILING_OPTIMAL, "usage" to usage,
            "sharingMode" to VK_SHARING_MODE_EXCLUSIVE, "initialLayout" to VK_IMAGE_LAYOUT_UNDEFINED),p)
        val img=p.get(JAVA_LONG,0)
        val req=s.struct(VkMemoryRequirements); dev.getImageMemoryRequirements(img,req)
        dev.allocateMemory(s.struct(VkMemoryAllocateInfo, "allocationSize" to req.u64(VkMemoryRequirements,"size"),
            "memoryTypeIndex" to memoryType(req.u32(VkMemoryRequirements,"memoryTypeBits"),VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)),p)
        val mem=p.get(JAVA_LONG,0)
        dev.bindImageMemory(img,mem,0)
        dev.createImageView(s.struct(VkImageViewCreateInfo, "image" to img, "viewType" to VK_IMAGE_VIEW_TYPE_2D, "format" to format,
            "subresourceRange.aspectMask" to aspect, "subresourceRange.levelCount" to 1, "subresourceRange.layerCount" to 1),p)
        Image(img,mem,p.get(JAVA_LONG,0))
    }

    private fun createRenderPass() = stack.frame { s ->
        val multi=echantillons>1
        val a=s.structs(VkAttachmentDescription,if(multi) 3 else 2)
        a.at(VkAttachmentDescription,0).put(VkAttachmentDescription, "format" to colorFormat, "samples" to echantillons,
            "loadOp" to VK_ATTACHMENT_LOAD_OP_CLEAR, "storeOp" to (if(multi) VK_ATTACHMENT_STORE_OP_DONT_CARE else VK_ATTACHMENT_STORE_OP_STORE),
            "stencilLoadOp" to VK_ATTACHMENT_LOAD_OP_DONT_CARE, "stencilStoreOp" to VK_ATTACHMENT_STORE_OP_DONT_CARE,
            "initialLayout" to VK_IMAGE_LAYOUT_UNDEFINED,
            "finalLayout" to (if(multi) VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL else VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL))
        a.at(VkAttachmentDescription,1).put(VkAttachmentDescription, "format" to VK_FORMAT_D32_SFLOAT, "samples" to echantillons,
            "loadOp" to VK_ATTACHMENT_LOAD_OP_CLEAR, "storeOp" to VK_ATTACHMENT_STORE_OP_DONT_CARE,
            "stencilLoadOp" to VK_ATTACHMENT_LOAD_OP_DONT_CARE, "stencilStoreOp" to VK_ATTACHMENT_STORE_OP_DONT_CARE,
            "initialLayout" to VK_IMAGE_LAYOUT_UNDEFINED, "finalLayout" to VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
        if(multi) a.at(VkAttachmentDescription,2).put(VkAttachmentDescription, "format" to colorFormat, "samples" to VK_SAMPLE_COUNT_1_BIT,
            "loadOp" to VK_ATTACHMENT_LOAD_OP_DONT_CARE, "storeOp" to VK_ATTACHMENT_STORE_OP_STORE,
            "stencilLoadOp" to VK_ATTACHMENT_LOAD_OP_DONT_CARE, "stencilStoreOp" to VK_ATTACHMENT_STORE_OP_DONT_CARE,
            "initialLayout" to VK_IMAGE_LAYOUT_UNDEFINED, "finalLayout" to VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
        val cr=s.struct(VkAttachmentReference, "attachment" to 0, "layout" to VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
        val dr=s.struct(VkAttachmentReference, "attachment" to 1, "layout" to VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
        val sub=s.struct(VkSubpassDescription, "pipelineBindPoint" to VK_PIPELINE_BIND_POINT_GRAPHICS,
            "colorAttachmentCount" to 1, "pColorAttachments" to cr, "pDepthStencilAttachment" to dr)
        if(multi) sub.put(VkSubpassDescription, "pResolveAttachments" to
            s.struct(VkAttachmentReference, "attachment" to 2, "layout" to VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL))
        val deps=s.structs(VkSubpassDependency,2)
        // Two frames in flight share the attachments: this frame waits for the last one's copy out of the
        // colour image and for its colour and depth writes.
        deps.at(VkSubpassDependency,0).put(VkSubpassDependency, "srcSubpass" to VK_SUBPASS_EXTERNAL, "dstSubpass" to 0,
            "srcStageMask" to (VK_PIPELINE_STAGE_TRANSFER_BIT or VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT or VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT),
            "dstStageMask" to (VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT or VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT),
            "srcAccessMask" to (VK_ACCESS_TRANSFER_READ_BIT or VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT or VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT),
            "dstAccessMask" to (VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT or VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT))
        deps.at(VkSubpassDependency,1).put(VkSubpassDependency, "srcSubpass" to 0, "dstSubpass" to VK_SUBPASS_EXTERNAL,
            "srcStageMask" to VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, "dstStageMask" to VK_PIPELINE_STAGE_TRANSFER_BIT,
            "srcAccessMask" to VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, "dstAccessMask" to VK_ACCESS_TRANSFER_READ_BIT)
        val p=s.allocate(JAVA_LONG)
        dev.createRenderPass(s.struct(VkRenderPassCreateInfo, "attachmentCount" to (if(multi) 3 else 2), "pAttachments" to a,
            "subpassCount" to 1, "pSubpasses" to sub, "dependencyCount" to 2, "pDependencies" to deps),p)
        renderPass=p.get(JAVA_LONG,0)
    }

    /** Images de rendu et framebuffer à la taille courante ; refaits quand la fenêtre change de taille. */
    private fun createTargets() = stack.frame { s ->
        if(framebuffer!=0L) { dev.destroyFramebuffer(framebuffer); framebuffer=0L }
        color?.let { destroy(it) }; msaa?.let { destroy(it) }; depth?.let { destroy(it) }
        color=image(colorFormat,VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK_IMAGE_USAGE_TRANSFER_SRC_BIT,VK_IMAGE_ASPECT_COLOR_BIT)
        msaa=if(echantillons>1) image(colorFormat,VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,VK_IMAGE_ASPECT_COLOR_BIT,echantillons) else null
        depth=image(VK_FORMAT_D32_SFLOAT,VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,VK_IMAGE_ASPECT_DEPTH_BIT,echantillons)
        val views=msaa?.let { s.longs(it.view,depth!!.view,color!!.view) } ?: s.longs(color!!.view,depth!!.view)
        val p=s.allocate(JAVA_LONG)
        dev.createFramebuffer(s.struct(VkFramebufferCreateInfo, "renderPass" to renderPass, "attachmentCount" to (if(msaa!=null) 3 else 2),
            "pAttachments" to views, "width" to largeur, "height" to hauteur, "layers" to 1),p)
        framebuffer=p.get(JAVA_LONG,0)
    }

    private fun shader(resource: String): Long = stack.frame { s ->
        val bytes=javaClass.getResourceAsStream(resource)?.use { it.readAllBytes() } ?: error("Shader missing: " + resource + " (run tools/shaders.sh)")
        val code=s.allocate(bytes.size.toLong(),4); MemorySegment.copy(bytes,0,code,JAVA_BYTE,0,bytes.size)
        val p=s.allocate(JAVA_LONG)
        dev.createShaderModule(s.struct(VkShaderModuleCreateInfo, "codeSize" to bytes.size.toLong(), "pCode" to code),p)
        p.get(JAVA_LONG,0)
    }

    private fun createPipeline() = stack.frame { s ->
        val vert=shader("/shaders/toon.vert.spv"); val frag=shader("/shaders/toon.frag.spv"); val fragOmbre=shader("/shaders/ombre.frag.spv")
        try {
            val stages=s.structs(VkPipelineShaderStageCreateInfo,2)
            stages.at(VkPipelineShaderStageCreateInfo,0).put(VkPipelineShaderStageCreateInfo,
                "stage" to VK_SHADER_STAGE_VERTEX_BIT, "module" to vert, "pName" to s.allocateFrom("main"))
            // Light and haze of the scene: specialisation constants 0–19 of toon.frag (floats).
            val values=lighting.constants()
            val data=s.allocate(JAVA_FLOAT,values.size.toLong())
            for((i,v) in values.withIndex()) data.setAtIndex(JAVA_FLOAT,i.toLong(),v)
            val entries=s.structs(VkSpecializationMapEntry,values.size)
            for(i in values.indices) entries.at(VkSpecializationMapEntry,i).put(VkSpecializationMapEntry, "constantID" to i, "offset" to i*4, "size" to 4L)
            val special=s.struct(VkSpecializationInfo, "mapEntryCount" to values.size, "pMapEntries" to entries, "dataSize" to values.size*4L, "pData" to data)
            stages.at(VkPipelineShaderStageCreateInfo,1).put(VkPipelineShaderStageCreateInfo,
                "stage" to VK_SHADER_STAGE_FRAGMENT_BIT, "module" to frag, "pName" to s.allocateFrom("main"), "pSpecializationInfo" to special)
            // Binding 0: the mesh (position, normal, colour); binding 1: one model matrix per copy, as three rows.
            val bindings=s.structs(VkVertexInputBindingDescription,2)
            bindings.at(VkVertexInputBindingDescription,0).put(VkVertexInputBindingDescription, "binding" to 0, "stride" to 36, "inputRate" to VK_VERTEX_INPUT_RATE_VERTEX)
            bindings.at(VkVertexInputBindingDescription,1).put(VkVertexInputBindingDescription, "binding" to 1, "stride" to 48, "inputRate" to VK_VERTEX_INPUT_RATE_INSTANCE)
            val attrs=s.structs(VkVertexInputAttributeDescription,6)
            for(i in 0..2) attrs.at(VkVertexInputAttributeDescription,i).put(VkVertexInputAttributeDescription,
                "binding" to 0, "location" to i, "format" to VK_FORMAT_R32G32B32_SFLOAT, "offset" to i*12)
            for(i in 0..2) attrs.at(VkVertexInputAttributeDescription,3+i).put(VkVertexInputAttributeDescription,
                "binding" to 1, "location" to 3+i, "format" to VK_FORMAT_R32G32B32A32_SFLOAT, "offset" to i*16)
            val vi=s.struct(VkPipelineVertexInputStateCreateInfo, "vertexBindingDescriptionCount" to 2, "pVertexBindingDescriptions" to bindings,
                "vertexAttributeDescriptionCount" to 6, "pVertexAttributeDescriptions" to attrs)
            val ia=s.struct(VkPipelineInputAssemblyStateCreateInfo, "topology" to VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
            // Viewport et scissor dynamiques : la taille suit la fenêtre sans refaire le pipeline.
            val vs=s.struct(VkPipelineViewportStateCreateInfo, "viewportCount" to 1, "scissorCount" to 1)
            val dyn=s.struct(VkPipelineDynamicStateCreateInfo, "dynamicStateCount" to 2,
                "pDynamicStates" to s.ints(VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR))
            val rs=s.struct(VkPipelineRasterizationStateCreateInfo, "polygonMode" to VK_POLYGON_MODE_FILL, "cullMode" to VK_CULL_MODE_NONE,
                "frontFace" to VK_FRONT_FACE_COUNTER_CLOCKWISE, "lineWidth" to 1f)
            val ms=s.struct(VkPipelineMultisampleStateCreateInfo, "rasterizationSamples" to echantillons)
            val ds=s.struct(VkPipelineDepthStencilStateCreateInfo, "depthTestEnable" to true, "depthWriteEnable" to true,
                "depthCompareOp" to VK_COMPARE_OP_LESS_OR_EQUAL)
            val ca=s.struct(VkPipelineColorBlendAttachmentState, "colorWriteMask" to
                (VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT))
            val cb=s.struct(VkPipelineColorBlendStateCreateInfo, "attachmentCount" to 1, "pAttachments" to ca)
            val push=s.struct(VkPushConstantRange, "stageFlags" to (VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT), "offset" to 0, "size" to 80)
            val p=s.allocate(JAVA_LONG)
            dev.createPipelineLayout(s.struct(VkPipelineLayoutCreateInfo, "pushConstantRangeCount" to 1, "pPushConstantRanges" to push),p)
            layout=p.get(JAVA_LONG,0)
            val ci=s.struct(VkGraphicsPipelineCreateInfo, "stageCount" to 2, "pStages" to stages, "pVertexInputState" to vi,
                "pInputAssemblyState" to ia, "pViewportState" to vs, "pRasterizationState" to rs, "pMultisampleState" to ms,
                "pDepthStencilState" to ds, "pColorBlendState" to cb, "pDynamicState" to dyn, "layout" to layout, "renderPass" to renderPass,
                "subpass" to 0, "basePipelineIndex" to -1)
            dev.createGraphicsPipelines(1,ci,p); pipeline=p.get(JAVA_LONG,0)
            // Ombres : même entrée, autre fragment, mélange par transparence, profondeur lue mais pas écrite.
            stages.at(VkPipelineShaderStageCreateInfo,1).put(VkPipelineShaderStageCreateInfo, "module" to fragOmbre)
            ds.put(VkPipelineDepthStencilStateCreateInfo, "depthWriteEnable" to false)
            ca.put(VkPipelineColorBlendAttachmentState, "blendEnable" to true,
                "srcColorBlendFactor" to VK_BLEND_FACTOR_SRC_ALPHA, "dstColorBlendFactor" to VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
                "colorBlendOp" to VK_BLEND_OP_ADD, "srcAlphaBlendFactor" to VK_BLEND_FACTOR_ZERO, "dstAlphaBlendFactor" to VK_BLEND_FACTOR_ONE,
                "alphaBlendOp" to VK_BLEND_OP_ADD)
            dev.createGraphicsPipelines(1,ci,p); pipelineOmbre=p.get(JAVA_LONG,0)
        } finally { dev.destroyShaderModule(vert); dev.destroyShaderModule(frag); dev.destroyShaderModule(fragOmbre) }
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
            var count=cap("minImageCount")+1
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
            swapchain=p.get(JAVA_LONG,0)
            dev.getSwapchainImagesKHR(swapchain,n,MemorySegment.NULL)
            val imgs=s.allocate(JAVA_LONG,n.get(JAVA_INT,0).toLong()); dev.getSwapchainImagesKHR(swapchain,n,imgs)
            swapImages=LongArray(n.get(JAVA_INT,0)) { imgs.getAtIndex(JAVA_LONG,it.toLong()) }
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
        batches.clear(); shadowBatches.clear()
        for(f in frames) f.hudSent=null
    }

    private fun growInstances(f: Frame, floats: Int) {
        f.instances?.let { dev.unmapMemory(it.memory); destroy(it) }
        val b=buffer(floats*4L,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
        f.instances=b
        f.mapped=stack.frame { s -> val p=s.allocate(ADDRESS); dev.mapMemory(b.memory,0,b.size,p); p.get(ADDRESS,0).reinterpret(b.size) }
    }

    /** Adds one copy of [mesh] with the model rows of [node] (times [pose] for a part of an animated model). */
    private fun add(map: IdentityHashMap<Mesh,Batch>, order: ArrayList<Batch>, mesh: Mesh, node: Node, pose: Mat4?) {
        if(mesh.vertices.isEmpty()) return
        val b=map.getOrPut(mesh) { Batch(mesh) }
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

    /**
     * Draws a frame of [objets] (without [hidden], the first-person player). [camera]: projection × view;
     * [oeil], the camera position, and [temps] (s) go to the shader for the rim light and the haze.
     */
    fun dessiner(objets: List<Node>, camera: Mat4, hud: Mesh, capture: Path?=null, oeil: Vec3 = Vec3.ZERO, temps: Float = 0f,
                 hidden: Node? = null) = stack.frame { s ->
        val f=frames[frameIndex]
        // This frame's resources are free again once the GPU is done with them, two frames ago.
        val fenceRef=s.longs(f.fence)
        dev.waitForFences(1,fenceRef,Long.MAX_VALUE)
        if(f.timed) {
            val t=s.allocate(JAVA_LONG,2)
            if(dev.getQueryPoolResults(queries,2*frameIndex,2,16,t,8,VK_QUERY_RESULT_64_BIT)==VK_SUCCESS) {
                var ticks=t.getAtIndex(JAVA_LONG,1)-t.getAtIndex(JAVA_LONG,0)
                if(timestampBits<64) ticks=ticks and ((1L shl timestampBits)-1)
                gpuMs=ticks*timestampPeriod/1e6
            }
        }
        var index=-1
        if(surface!=0L) {
            val out=s.allocate(JAVA_INT)
            val result=dev.acquireNextImageKHR(swapchain,Long.MAX_VALUE,f.acquired,VK_NULL_HANDLE,out)
            if(result==VK_ERROR_OUT_OF_DATE_KHR) { recreateSwapchain(); return@frame }
            check(result==VK_SUCCESS || result==VK_SUBOPTIMAL_KHR) { "vkAcquireNextImageKHR: " + result }
            index=out.get(JAVA_INT,0)
        }
        // New meshes go to the GPU once; then every copy of a mesh joins its batch.
        fun prepare(m: Mesh) {
            if(m.vertices.isEmpty() || meshes.containsKey(m)) return
            val b=buffer(m.vertices.size.toLong()*4,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
            upload(b,m.vertices); meshes[m]=b
        }
        for(b in opaque) b.count=0
        for(b in shadows) b.count=0
        opaque.clear(); shadows.clear()
        for(o in objets) {
            if(!o.visible || o===hidden) continue
            if(o.isShadow) { prepare(o.mesh); add(shadowBatches,shadows,o.mesh,o,null); continue }
            prepare(o.mesh); add(batches,opaque,o.mesh,o,null)
            for(p in o.parts) { prepare(p.mesh); add(batches,opaque,p.mesh,o,p.pose) }
        }
        if(hud!==f.hudSent) { upload(f.hud!!,hud.vertices); f.hudSent=hud }
        // Instance rows of every batch, side by side, then the HUD's identity.
        var total=12
        for(b in opaque) total+=12*b.count
        for(b in shadows) total+=12*b.count
        if(total*4L>f.instances!!.size) growInstances(f,Integer.highestOneBit(total)*2)
        val mapped=f.mapped
        var at=0L
        fun put(b: Batch): Long { val start=at; MemorySegment.copy(b.rows,0,mapped,JAVA_FLOAT,at,12*b.count); at+=48L*b.count; return start }
        val readback=if(capture!=null) buffer(largeur.toLong()*hauteur*4,VK_BUFFER_USAGE_TRANSFER_DST_BIT) else null
        val command=f.command
        try {
            dev.resetCommandBuffer(command,0)
            dev.beginCommandBuffer(command,s.struct(VkCommandBufferBeginInfo, "flags" to VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT))
            if(queries!=0L) {
                dev.cmdResetQueryPool(command,queries,2*frameIndex,2)
                dev.cmdWriteTimestamp(command,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,queries,2*frameIndex)
            }
            val clear=s.structs(VkClearValue,2)
            clear.at(VkClearValue,0).put(VkClearValue, "float32[0]" to .42f, "float32[1]" to .73f, "float32[2]" to .82f, "float32[3]" to 1f)
            clear.at(VkClearValue,1).put(VkClearDepthStencilValue, "depth" to 1f, "stencil" to 0)
            val ri=s.struct(VkRenderPassBeginInfo, "renderPass" to renderPass, "framebuffer" to framebuffer,
                "renderArea.extent.width" to largeur, "renderArea.extent.height" to hauteur, "clearValueCount" to 2, "pClearValues" to clear)
            dev.cmdBeginRenderPass(command,ri,VK_SUBPASS_CONTENTS_INLINE)
            dev.cmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline)
            dev.cmdSetViewport(command,s.struct(VkViewport, "width" to largeur.toFloat(), "height" to hauteur.toFloat(), "maxDepth" to 1f))
            dev.cmdSetScissor(command,s.struct(VkRect2D, "extent.width" to largeur, "extent.height" to hauteur))
            // 80 bytes: view × projection (16), eye and time (4).
            val constants=s.allocate(JAVA_FLOAT,20)
            fun pushConstants(vp: Mat4) {
                // Column by column, as GLSL reads a mat4 (Mat4 names its coefficients column first).
                constants.setAtIndex(JAVA_FLOAT,0,vp.m00); constants.setAtIndex(JAVA_FLOAT,1,vp.m01); constants.setAtIndex(JAVA_FLOAT,2,vp.m02); constants.setAtIndex(JAVA_FLOAT,3,vp.m03)
                constants.setAtIndex(JAVA_FLOAT,4,vp.m10); constants.setAtIndex(JAVA_FLOAT,5,vp.m11); constants.setAtIndex(JAVA_FLOAT,6,vp.m12); constants.setAtIndex(JAVA_FLOAT,7,vp.m13)
                constants.setAtIndex(JAVA_FLOAT,8,vp.m20); constants.setAtIndex(JAVA_FLOAT,9,vp.m21); constants.setAtIndex(JAVA_FLOAT,10,vp.m22); constants.setAtIndex(JAVA_FLOAT,11,vp.m23)
                constants.setAtIndex(JAVA_FLOAT,12,vp.m30); constants.setAtIndex(JAVA_FLOAT,13,vp.m31); constants.setAtIndex(JAVA_FLOAT,14,vp.m32); constants.setAtIndex(JAVA_FLOAT,15,vp.m33)
                constants.setAtIndex(JAVA_FLOAT,16,oeil.x); constants.setAtIndex(JAVA_FLOAT,17,oeil.y); constants.setAtIndex(JAVA_FLOAT,18,oeil.z); constants.setAtIndex(JAVA_FLOAT,19,temps)
                dev.cmdPushConstants(command,layout,VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT,0,80,constants)
            }
            val handles=s.allocate(JAVA_LONG,2); val offsets=s.allocate(JAVA_LONG,2)
            handles.setAtIndex(JAVA_LONG,1,f.instances!!.buffer)
            fun draw(vertices: Buffer, count: Int, vertexCount: Int, instanceOffset: Long) {
                handles.setAtIndex(JAVA_LONG,0,vertices.buffer); offsets.setAtIndex(JAVA_LONG,0,0L); offsets.setAtIndex(JAVA_LONG,1,instanceOffset)
                dev.cmdBindVertexBuffers(command,0,2,handles,offsets)
                dev.cmdDraw(command,vertexCount,count,0,0)
            }
            pushConstants(camera)
            for(b in opaque) { val start=put(b); draw(meshes[b.mesh]!!,b.count,b.mesh.vertices.size/9,start) }
            dev.cmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipelineOmbre)
            for(b in shadows) { val start=put(b); draw(meshes[b.mesh]!!,b.count,b.mesh.vertices.size/9,start) }
            dev.cmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline)
            if(hud.vertices.isNotEmpty()) {
                val start=at
                for(k in 0 until 12) mapped.setAtIndex(JAVA_FLOAT,at/4+k,IDENTITY_ROWS[k])
                at+=48
                pushConstants(Mat4.IDENTITY)
                draw(f.hud!!,1,hud.vertices.size/9,start)
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
                val result=dev.queuePresentKHR(queue,pi)
                if(result==VK_ERROR_OUT_OF_DATE_KHR || result==VK_SUBOPTIMAL_KHR) recreateSwapchain() else result.vkCheck("Present")
            }
            if(capture!=null && readback!=null) { dev.waitForFences(1,fenceRef,Long.MAX_VALUE); save(readback,capture) }
        } finally { if(readback!=null) { dev.waitForFences(1,fenceRef,Long.MAX_VALUE); destroy(readback) } }
    }

    private fun save(b: Buffer, destination: Path) = stack.frame { s ->
        val p=s.allocate(ADDRESS); dev.mapMemory(b.memory,0,b.size,p)
        try {
            val bytes=p.get(ADDRESS,0).reinterpret(b.size)
            val img=BufferedImage(largeur,hauteur,BufferedImage.TYPE_INT_RGB)
            for(y in 0 until hauteur) for(x in 0 until largeur) {
                val i=((y*largeur+x)*4).toLong()
                img.setRGB(x,y,((bytes.get(JAVA_BYTE,i).toInt() and 255) shl 16) or ((bytes.get(JAVA_BYTE,i+1).toInt() and 255) shl 8) or (bytes.get(JAVA_BYTE,i+2).toInt() and 255))
            }
            destination.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            check(ImageIO.write(img,"png",destination.toFile()))
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
                for(f in frames) {
                    f.hud?.let { destroy(it) }; f.instances?.let { dev.unmapMemory(it.memory); destroy(it) }
                    dev.destroyFence(f.fence); dev.destroySemaphore(f.acquired)
                }
                frames.clear()
                if(queries!=0L) dev.destroyQueryPool(queries)
                dev.destroyPipeline(pipeline); dev.destroyPipeline(pipelineOmbre)
                dev.destroyPipelineLayout(layout)
                dev.destroyFramebuffer(framebuffer)
                dev.destroyRenderPass(renderPass)
                color?.let { destroy(it) }; msaa?.let { destroy(it) }; depth?.let { destroy(it) }; color=null; msaa=null; depth=null
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
}
