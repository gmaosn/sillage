package sillage

import org.joml.Matrix4f
import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFWVulkan.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.KHRSurface.*
import org.lwjgl.vulkan.KHRSwapchain.*
import org.lwjgl.vulkan.EXTDebugUtils.*
import java.nio.ByteBuffer
import java.nio.file.Path
import java.nio.file.Files
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.util.IdentityHashMap

/** Backend Vulkan direct. Une image en vol : synchronisation volontairement simple pour la v0.1.
 * Le même render pass fonctionne avec une fenêtre GLFW ou sans serveur graphique. */
class VulkanRenderer(private val window: Long = 0L, private val validation: Boolean = false) : AutoCloseable {
    val largeur=960
    val hauteur=600
    private lateinit var instance: VkInstance
    private lateinit var physical: VkPhysicalDevice
    private lateinit var device: VkDevice
    private lateinit var queue: VkQueue
    private var family=0
    private var surface=0L
    private var swapchain=0L
    private var swapImages=LongArray(0)
    private var swapWidth=0
    private var swapHeight=0
    private var colorFormat=VK_FORMAT_R8G8B8A8_UNORM
    private var pool=0L
    private lateinit var command: VkCommandBuffer
    private var renderPass=0L
    private var framebuffer=0L
    private var layout=0L
    private var pipeline=0L
    private var acquired=0L
    private var finished=0L
    private var messenger=0L
    private var debug: VkDebugUtilsMessengerCallbackEXT? = null
    var erreursValidation=0
        private set
    private data class Image(val image: Long, val memory: Long, val view: Long)
    private data class Buffer(val buffer: Long, val memory: Long, val size: Long)
    private var color: Image?=null
    private var depth: Image?=null
    private var hudBuffer: Buffer?=null
    private val meshes=IdentityHashMap<Maillage,Buffer>()
    private var ferme=false
    var nomGpu=""
        private set

    private fun check(result: Int, operation: String) { check(result == VK_SUCCESS) { operation + " : VkResult=" + result } }
    private inline fun <T> stack(block: (MemoryStack)->T): T = MemoryStack.stackPush().use(block)

    fun ouvrir() {
        try {
            createInstance()
            if(window != 0L) stack { s -> val p=s.mallocLong(1); check(glfwCreateWindowSurface(instance,window,null,p),"Surface GLFW"); surface=p[0] }
            chooseDevice()
            createDevice()
            stack { s ->
                val info=VkCommandPoolCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .queueFamilyIndex(family).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                val p=s.mallocLong(1); check(vkCreateCommandPool(device,info,null,p),"Command pool"); pool=p[0]
                val ai=VkCommandBufferAllocateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1)
                val out=s.mallocPointer(1); check(vkAllocateCommandBuffers(device,ai,out),"Command buffer"); command=VkCommandBuffer(out[0],device)
                val si=VkSemaphoreCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
                check(vkCreateSemaphore(device,si,null,p),"Semaphore"); acquired=p[0]
                check(vkCreateSemaphore(device,si,null,p),"Semaphore"); finished=p[0]
            }
            color=image(colorFormat,VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK_IMAGE_USAGE_TRANSFER_SRC_BIT,VK_IMAGE_ASPECT_COLOR_BIT)
            depth=image(VK_FORMAT_D32_SFLOAT,VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,VK_IMAGE_ASPECT_DEPTH_BIT)
            createRenderPass()
            createPipeline()
            hudBuffer=buffer(4L*1024*1024,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
            if(surface != 0L) recreateSwapchain()
            println("SILLAGE_VULKAN_OK GPU=" + nomGpu + " target=" + largeur + "x" + hauteur + " validation=" + validation)
        } catch(e: Throwable) { close(); throw e }
    }

    private fun createInstance() = stack { s ->
        if(validation) {
            val n=s.mallocInt(1); check(vkEnumerateInstanceLayerProperties(n,null),"Couches Vulkan")
            val layers=VkLayerProperties.calloc(n[0],s); check(vkEnumerateInstanceLayerProperties(n,layers),"Couches Vulkan")
            require((0 until n[0]).any { layers[it].layerNameString()=="VK_LAYER_KHRONOS_validation" }) {
                "Installer vulkan-validation-layers pour utiliser --validation" }
        }
        val app=VkApplicationInfo.calloc(s).sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
            .pApplicationName(s.UTF8("Sillage")).pEngineName(s.UTF8("Sillage Kotlin"))
            .apiVersion(VK_API_VERSION_1_0)
        val required: PointerBuffer? = if(window != 0L) requireNotNull(glfwGetRequiredInstanceExtensions()) else null
        val names=s.mallocPointer((required?.remaining() ?: 0)+(if(validation) 1 else 0))
        if(required != null) names.put(required)
        if(validation) names.put(s.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME))
        names.flip()
        val ci=VkInstanceCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO).pApplicationInfo(app)
        if(names.hasRemaining()) ci.ppEnabledExtensionNames(names)
        if(validation) ci.ppEnabledLayerNames(s.pointers(s.UTF8("VK_LAYER_KHRONOS_validation")))
        val p=s.mallocPointer(1); check(vkCreateInstance(ci,null,p),"Instance Vulkan"); instance=VkInstance(p[0],ci)
        if(validation) {
            debug=VkDebugUtilsMessengerCallbackEXT.create { severity, _, data, _ ->
                val message=VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString()
                if(severity and VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT != 0) erreursValidation++
                System.err.println("Vulkan: " + message)
                VK_FALSE
            }
            val di=VkDebugUtilsMessengerCreateInfoEXT.calloc(s).sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
                .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT)
                .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                .pfnUserCallback(debug!!)
            val out=s.mallocLong(1); check(vkCreateDebugUtilsMessengerEXT(instance,di,null,out),"Validation"); messenger=out[0]
        }
    }

    private fun chooseDevice() = stack { s ->
        val n=s.mallocInt(1); check(vkEnumeratePhysicalDevices(instance,n,null),"GPU count")
        require(n[0]>0) { "Aucun pilote Vulkan disponible" }
        val devices=s.mallocPointer(n[0]); check(vkEnumeratePhysicalDevices(instance,n,devices),"GPU list")
        var best=-1
        for(i in 0 until n[0]) {
            val candidate=VkPhysicalDevice(devices[i],instance)
            val count=s.mallocInt(1); vkGetPhysicalDeviceQueueFamilyProperties(candidate,count,null)
            val props=VkQueueFamilyProperties.calloc(count[0],s); vkGetPhysicalDeviceQueueFamilyProperties(candidate,count,props)
            val dp=VkPhysicalDeviceProperties.calloc(s); vkGetPhysicalDeviceProperties(candidate,dp)
            for(j in 0 until count[0]) {
                if(props[j].queueFlags() and VK_QUEUE_GRAPHICS_BIT == 0) continue
                if(surface != 0L) {
                    val support=s.mallocInt(1); check(vkGetPhysicalDeviceSurfaceSupportKHR(candidate,j,surface,support),"Présentation")
                    if(support[0] != VK_TRUE) continue
                }
                val score=when(dp.deviceType()) {VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU->100; VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU->50; else->1}
                if(score>best) { best=score; physical=candidate; family=j; nomGpu=dp.deviceNameString() }
            }
        }
        require(best>=0) { "Aucune queue graphique Vulkan compatible avec la fenêtre" }
        val fp=VkFormatProperties.calloc(s); vkGetPhysicalDeviceFormatProperties(physical,VK_FORMAT_D32_SFLOAT,fp)
        require(fp.optimalTilingFeatures() and VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT != 0) { "D32 non pris en charge" }
    }

    private fun createDevice() = stack { s ->
        val queues=VkDeviceQueueCreateInfo.calloc(1,s)
        queues[0].sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO).queueFamilyIndex(family).pQueuePriorities(s.floats(1f))
        val ci=VkDeviceCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO).pQueueCreateInfos(queues)
        if(surface != 0L) ci.ppEnabledExtensionNames(s.pointers(s.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME)))
        val p=s.mallocPointer(1); check(vkCreateDevice(physical,ci,null,p),"Device Vulkan"); device=VkDevice(p[0],physical,ci)
        vkGetDeviceQueue(device,family,0,p); queue=VkQueue(p[0],device)
    }

    private fun memoryType(bits: Int, flags: Int): Int = stack { s ->
        val props=VkPhysicalDeviceMemoryProperties.calloc(s); vkGetPhysicalDeviceMemoryProperties(physical,props)
        (0 until props.memoryTypeCount()).firstOrNull { bits and (1 shl it) != 0 && props.memoryTypes(it).propertyFlags() and flags == flags }
            ?: error("Type mémoire Vulkan indisponible, flags=" + flags)
    }

    private fun buffer(size: Long, usage: Int): Buffer = stack { s ->
        val info=VkBufferCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO).size(size)
            .usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
        val p=s.mallocLong(1); check(vkCreateBuffer(device,info,null,p),"Buffer"); val handle=p[0]
        val req=VkMemoryRequirements.calloc(s); vkGetBufferMemoryRequirements(device,handle,req)
        val alloc=VkMemoryAllocateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO).allocationSize(req.size())
            .memoryTypeIndex(memoryType(req.memoryTypeBits(),VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))
        check(vkAllocateMemory(device,alloc,null,p),"Mémoire buffer"); val memory=p[0]
        check(vkBindBufferMemory(device,handle,memory,0),"Bind buffer")
        Buffer(handle,memory,size)
    }

    private fun upload(b: Buffer, vertices: FloatArray) = stack { s ->
        require(vertices.size.toLong()*4<=b.size)
        val p=s.mallocPointer(1); check(vkMapMemory(device,b.memory,0,b.size,0,p),"Map vertices")
        try { MemoryUtil.memFloatBuffer(p[0],vertices.size).put(vertices) } finally { vkUnmapMemory(device,b.memory) }
    }

    private fun image(format: Int, usage: Int, aspect: Int): Image = stack { s ->
        val ci=VkImageCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO).imageType(VK_IMAGE_TYPE_2D)
            .format(format).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
            .usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
        ci.extent().set(largeur,hauteur,1)
        val p=s.mallocLong(1); check(vkCreateImage(device,ci,null,p),"Image"); val img=p[0]
        val req=VkMemoryRequirements.calloc(s); vkGetImageMemoryRequirements(device,img,req)
        val ai=VkMemoryAllocateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO).allocationSize(req.size())
            .memoryTypeIndex(memoryType(req.memoryTypeBits(),VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
        check(vkAllocateMemory(device,ai,null,p),"Mémoire image"); val mem=p[0]
        check(vkBindImageMemory(device,img,mem,0),"Bind image")
        val vi=VkImageViewCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO).image(img).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format)
        vi.subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)
        check(vkCreateImageView(device,vi,null,p),"Image view")
        Image(img,mem,p[0])
    }

    private fun createRenderPass() = stack { s ->
        val a=VkAttachmentDescription.calloc(2,s)
        a[0].format(colorFormat).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
            .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
        a[1].format(VK_FORMAT_D32_SFLOAT).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
        val cr=VkAttachmentReference.calloc(1,s); cr[0].attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
        val dr=VkAttachmentReference.calloc(s).attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
        val sub=VkSubpassDescription.calloc(1,s); sub[0].pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1).pColorAttachments(cr).pDepthStencilAttachment(dr)
        val deps=VkSubpassDependency.calloc(2,s)
        deps[0].srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0).srcStageMask(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
            .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT or VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
            .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT or VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
        deps[1].srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL).srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
            .dstStageMask(VK_PIPELINE_STAGE_TRANSFER_BIT).srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
        val ci=VkRenderPassCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO).pAttachments(a).pSubpasses(sub).pDependencies(deps)
        val p=s.mallocLong(1); check(vkCreateRenderPass(device,ci,null,p),"Render pass"); renderPass=p[0]
        val fi=VkFramebufferCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO).renderPass(renderPass)
            .pAttachments(s.longs(color!!.view,depth!!.view)).width(largeur).height(hauteur).layers(1)
        check(vkCreateFramebuffer(device,fi,null,p),"Framebuffer"); framebuffer=p[0]
    }

    private fun shader(resource: String): Long = stack { s ->
        val bytes=javaClass.getResourceAsStream(resource)?.use { it.readAllBytes() } ?: error("Shader absent : " + resource + "; lancer tools/shaders.sh")
        val code=MemoryUtil.memAlloc(bytes.size).put(bytes).flip()
        try {
            val ci=VkShaderModuleCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO).pCode(code)
            val p=s.mallocLong(1); check(vkCreateShaderModule(device,ci,null,p),"Shader module"); p[0]
        } finally { MemoryUtil.memFree(code) }
    }

    private fun createPipeline() = stack { s ->
        val vert=shader("/shaders/toon.vert.spv"); val frag=shader("/shaders/toon.frag.spv")
        try {
            val stages=VkPipelineShaderStageCreateInfo.calloc(2,s)
            stages[0].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(s.UTF8("main"))
            stages[1].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(s.UTF8("main"))
            val binding=VkVertexInputBindingDescription.calloc(1,s); binding[0].binding(0).stride(36).inputRate(VK_VERTEX_INPUT_RATE_VERTEX)
            val attrs=VkVertexInputAttributeDescription.calloc(3,s)
            for(i in 0..2) attrs[i].binding(0).location(i).format(VK_FORMAT_R32G32B32_SFLOAT).offset(i*12)
            val vi=VkPipelineVertexInputStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                .pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attrs)
            val ia=VkPipelineInputAssemblyStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
            val vp=VkViewport.calloc(1,s); vp[0].x(0f).y(0f).width(largeur.toFloat()).height(hauteur.toFloat()).minDepth(0f).maxDepth(1f)
            val sc=VkRect2D.calloc(1,s); sc[0].offset().set(0,0); sc[0].extent().set(largeur,hauteur)
            val vs=VkPipelineViewportStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).pViewports(vp).pScissors(sc)
            val rs=VkPipelineRasterizationStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f)
            val ms=VkPipelineMultisampleStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO).rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
            val ds=VkPipelineDepthStencilStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                .depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL)
            val ca=VkPipelineColorBlendAttachmentState.calloc(1,s)
            ca[0].colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT)
            val cb=VkPipelineColorBlendStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).pAttachments(ca)
            val push=VkPushConstantRange.calloc(1,s); push[0].stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(128)
            val li=VkPipelineLayoutCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO).pPushConstantRanges(push)
            val p=s.mallocLong(1); check(vkCreatePipelineLayout(device,li,null,p),"Pipeline layout"); layout=p[0]
            val ci=VkGraphicsPipelineCreateInfo.calloc(1,s)
            ci[0].sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO).pStages(stages).pVertexInputState(vi).pInputAssemblyState(ia)
                .pViewportState(vs).pRasterizationState(rs).pMultisampleState(ms).pDepthStencilState(ds).pColorBlendState(cb)
                .layout(layout).renderPass(renderPass).subpass(0).basePipelineIndex(-1)
            check(vkCreateGraphicsPipelines(device,VK_NULL_HANDLE,ci,null,p),"Pipeline graphique"); pipeline=p[0]
        } finally { vkDestroyShaderModule(device,vert,null); vkDestroyShaderModule(device,frag,null) }
    }

    fun recreateSwapchain(): Boolean {
        if(surface == 0L) return true
        return stack { s ->
            val w=s.mallocInt(1); val h=s.mallocInt(1)
            org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window,w,h)
            if(w[0]==0 || h[0]==0) return@stack false
            check(vkDeviceWaitIdle(device),"Attente resize")
            val caps=VkSurfaceCapabilitiesKHR.calloc(s); check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical,surface,caps),"Surface caps")
            require(caps.supportedUsageFlags() and VK_IMAGE_USAGE_TRANSFER_DST_BIT != 0) { "Surface sans transfert destination" }
            val n=s.mallocInt(1); check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical,surface,n,null),"Surface formats")
            val formats=VkSurfaceFormatKHR.calloc(n[0],s); check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical,surface,n,formats),"Formats")
            val chosen=(0 until n[0]).firstOrNull { formats[it].format()==VK_FORMAT_B8G8R8A8_UNORM } ?: 0
            val format=if(formats[chosen].format()==VK_FORMAT_UNDEFINED) VK_FORMAT_B8G8R8A8_UNORM else formats[chosen].format()
            swapWidth=if(caps.currentExtent().width()!=-1) caps.currentExtent().width() else w[0].coerceIn(caps.minImageExtent().width(),caps.maxImageExtent().width())
            swapHeight=if(caps.currentExtent().height()!=-1) caps.currentExtent().height() else h[0].coerceIn(caps.minImageExtent().height(),caps.maxImageExtent().height())
            var count=caps.minImageCount()+1
            if(caps.maxImageCount()>0) count=minOf(count,caps.maxImageCount())
            val composite=listOf(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR)
                .first { caps.supportedCompositeAlpha() and it != 0 }
            val ci=VkSwapchainCreateInfoKHR.calloc(s).sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR).surface(surface)
                .minImageCount(count).imageFormat(format).imageColorSpace(formats[chosen].colorSpace()).imageArrayLayers(1)
                .imageUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT).imageSharingMode(VK_SHARING_MODE_EXCLUSIVE).preTransform(caps.currentTransform())
                .compositeAlpha(composite).presentMode(VK_PRESENT_MODE_FIFO_KHR).clipped(true).oldSwapchain(swapchain)
            ci.imageExtent().set(swapWidth,swapHeight)
            val p=s.mallocLong(1); check(vkCreateSwapchainKHR(device,ci,null,p),"Swapchain")
            if(swapchain!=0L) vkDestroySwapchainKHR(device,swapchain,null)
            swapchain=p[0]
            check(vkGetSwapchainImagesKHR(device,swapchain,n,null),"Swapchain images")
            val imgs=s.mallocLong(n[0]); check(vkGetSwapchainImagesKHR(device,swapchain,n,imgs),"Images")
            swapImages=LongArray(n[0]) { imgs[it] }
            true
        }
    }

    private fun barrier(image: Long, old: Int, new: Int, srcAccess: Int, dstAccess: Int, srcStage: Int, dstStage: Int, s: MemoryStack) {
        val b=VkImageMemoryBarrier.calloc(1,s)
        b[0].sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER).oldLayout(old).newLayout(new).srcAccessMask(srcAccess).dstAccessMask(dstAccess)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image)
        b[0].subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)
        vkCmdPipelineBarrier(command,srcStage,dstStage,0,null,null,b)
    }

    fun dessiner(objets: List<Objet>, camera: Matrix4f, hud: Maillage, capture: Path?=null) = stack { s ->
        var index=-1
        if(surface!=0L) {
            val out=s.mallocInt(1)
            val result=vkAcquireNextImageKHR(device,swapchain,Long.MAX_VALUE,acquired,VK_NULL_HANDLE,out)
            if(result==VK_ERROR_OUT_OF_DATE_KHR) { recreateSwapchain(); return@stack }
            check(result==VK_SUCCESS || result==VK_SUBOPTIMAL_KHR) { "Acquisition image : " + result }
            index=out[0]
        }
        // La frame précédente est terminée avant toute écriture de mémoire hôte.
        for(o in objets) if(o.visible && o.maillage.sommets.isNotEmpty() && !meshes.containsKey(o.maillage)) {
            val b=buffer(o.maillage.sommets.size.toLong()*4,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
            upload(b,o.maillage.sommets); meshes[o.maillage]=b
        }
        upload(hudBuffer!!,hud.sommets)
        val readback=if(capture!=null) buffer(largeur.toLong()*hauteur*4,VK_BUFFER_USAGE_TRANSFER_DST_BIT) else null
        try {
            check(vkResetCommandBuffer(command,0),"Reset command")
            val begin=VkCommandBufferBeginInfo.calloc(s).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO).flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
            check(vkBeginCommandBuffer(command,begin),"Begin command")
            val clear=VkClearValue.calloc(2,s); clear[0].color().float32(0,.42f).float32(1,.73f).float32(2,.82f).float32(3,1f)
            clear[1].depthStencil().set(1f,0)
            val ri=VkRenderPassBeginInfo.calloc(s).sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO).renderPass(renderPass).framebuffer(framebuffer).pClearValues(clear)
            ri.renderArea().offset().set(0,0); ri.renderArea().extent().set(largeur,hauteur)
            vkCmdBeginRenderPass(command,ri,VK_SUBPASS_CONTENTS_INLINE)
            vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline)
            val constants=s.mallocFloat(32)
            val handle=s.mallocLong(1); val offsets=s.longs(0L)
            fun draw(b: Buffer, mesh: Maillage, model: Matrix4f, vp: Matrix4f) {
                constants.clear(); Matrix4f(vp).mul(model).get(0,constants); model.get(16,constants)
                vkCmdPushConstants(command,layout,VK_SHADER_STAGE_VERTEX_BIT,0,constants)
                handle.put(0,b.buffer); vkCmdBindVertexBuffers(command,0,handle,offsets)
                vkCmdDraw(command,mesh.sommets.size/9,1,0,0)
            }
            for(o in objets) if(o.visible) meshes[o.maillage]?.let { draw(it,o.maillage,o.matrice(),camera) }
            draw(hudBuffer!!,hud,Matrix4f(),Matrix4f())
            vkCmdEndRenderPass(command)
            if(readback!=null) {
                val region=VkBufferImageCopy.calloc(1,s)
                region[0].bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
                region[0].imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1)
                region[0].imageOffset().set(0,0,0); region[0].imageExtent().set(largeur,hauteur,1)
                vkCmdCopyImageToBuffer(command,color!!.image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,readback.buffer,region)
                val mb=VkMemoryBarrier.calloc(1,s); mb[0].sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,mb,null,null)
            }
            if(index>=0) {
                val img=swapImages[index]
                barrier(img,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,0,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,s)
                val blit=VkImageBlit.calloc(1,s)
                blit[0].srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1)
                blit[0].srcOffsets(0).set(0,0,0); blit[0].srcOffsets(1).set(largeur,hauteur,1)
                blit[0].dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1)
                blit[0].dstOffsets(0).set(0,0,0); blit[0].dstOffsets(1).set(swapWidth,swapHeight,1)
                vkCmdBlitImage(command,color!!.image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,img,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,blit,VK_FILTER_NEAREST)
                barrier(img,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,VK_ACCESS_TRANSFER_WRITE_BIT,0,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,s)
            }
            check(vkEndCommandBuffer(command),"End command")
            val submit=VkSubmitInfo.calloc(s).sType(VK_STRUCTURE_TYPE_SUBMIT_INFO).pCommandBuffers(s.pointers(command.address()))
            if(index>=0) submit.pWaitSemaphores(s.longs(acquired)).pWaitDstStageMask(s.ints(VK_PIPELINE_STAGE_TRANSFER_BIT)).pSignalSemaphores(s.longs(finished))
            check(vkQueueSubmit(queue,submit,VK_NULL_HANDLE),"Queue submit")
            if(index>=0) {
                val pi=VkPresentInfoKHR.calloc(s).sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR).pWaitSemaphores(s.longs(finished))
                    .swapchainCount(1).pSwapchains(s.longs(swapchain)).pImageIndices(s.ints(index))
                val result=vkQueuePresentKHR(queue,pi)
                check(vkQueueWaitIdle(queue),"Frame fence")
                if(result==VK_ERROR_OUT_OF_DATE_KHR || result==VK_SUBOPTIMAL_KHR) recreateSwapchain() else check(result,"Présentation")
            } else check(vkQueueWaitIdle(queue),"Offscreen fence")
            if(capture!=null && readback!=null) save(readback,capture)
        } finally { if(readback!=null) destroy(readback) }
    }

    private fun save(b: Buffer, destination: Path) = stack { s ->
        val p=s.mallocPointer(1); check(vkMapMemory(device,b.memory,0,b.size,0,p),"Readback")
        try {
            val bytes=MemoryUtil.memByteBuffer(p[0],b.size.toInt())
            val img=BufferedImage(largeur,hauteur,BufferedImage.TYPE_INT_RGB)
            for(y in 0 until hauteur) for(x in 0 until largeur) {
                val i=(y*largeur+x)*4
                img.setRGB(x,y,((bytes.get(i).toInt() and 255) shl 16) or ((bytes.get(i+1).toInt() and 255) shl 8) or (bytes.get(i+2).toInt() and 255))
            }
            destination.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            check(ImageIO.write(img,"png",destination.toFile()))
            println("SILLAGE_CAPTURE=" + destination.toAbsolutePath())
        } finally { vkUnmapMemory(device,b.memory) }
    }
    private fun destroy(b: Buffer) { vkDestroyBuffer(device,b.buffer,null); vkFreeMemory(device,b.memory,null) }
    private fun destroy(i: Image) { vkDestroyImageView(device,i.view,null); vkDestroyImage(device,i.image,null); vkFreeMemory(device,i.memory,null) }

    override fun close() {
        if(ferme) return
        ferme=true
        if(::device.isInitialized) {
            vkDeviceWaitIdle(device)
            meshes.values.forEach { destroy(it) }; meshes.clear()
            hudBuffer?.let { destroy(it) }; hudBuffer=null
            if(pipeline!=0L) vkDestroyPipeline(device,pipeline,null)
            if(layout!=0L) vkDestroyPipelineLayout(device,layout,null)
            if(framebuffer!=0L) vkDestroyFramebuffer(device,framebuffer,null)
            if(renderPass!=0L) vkDestroyRenderPass(device,renderPass,null)
            color?.let { destroy(it) }; depth?.let { destroy(it) }; color=null; depth=null
            if(swapchain!=0L) vkDestroySwapchainKHR(device,swapchain,null)
            if(acquired!=0L) vkDestroySemaphore(device,acquired,null)
            if(finished!=0L) vkDestroySemaphore(device,finished,null)
            if(pool!=0L) vkDestroyCommandPool(device,pool,null)
            vkDestroyDevice(device,null)
        }
        if(::instance.isInitialized) {
            if(surface!=0L) vkDestroySurfaceKHR(instance,surface,null)
            if(messenger!=0L) vkDestroyDebugUtilsMessengerEXT(instance,messenger,null)
            debug?.free(); debug=null
            vkDestroyInstance(instance,null)
        }
    }
}
