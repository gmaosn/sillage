package sillage.vk

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/*
 * Vulkan through the JDK's own Foreign Function & Memory API: no JNI, no binding library.
 *
 * Each C signature shape has one downcall handle held in a static final field, so the JIT treats it
 * as a constant and inlines the call; the function's address is passed as the first argument.
 * Device-level functions come from vkGetDeviceProcAddr, which skips the loader's dispatch trampoline.
 *
 * Letters after the underscore: P pointer or dispatchable handle (MemorySegment), I 32-bit int,
 * J 64-bit integer (VkDeviceSize, size_t, non-dispatchable handle). The prefix is the return type.
 */
@Suppress("PropertyName")
internal object Sig {
    private val linker = Linker.nativeLinker()
    private fun h(ret: MemoryLayout?, vararg args: MemoryLayout): MethodHandle = linker.downcallHandle(
        if (ret == null) FunctionDescriptor.ofVoid(*args) else FunctionDescriptor.of(ret, *args))
    private val P = ADDRESS
    private val I = JAVA_INT
    private val J = JAVA_LONG

    @JvmField val P_PP = h(P, P, P)
    @JvmField val I_P = h(I, P)
    @JvmField val I_PI = h(I, P, I)
    @JvmField val I_PP = h(I, P, P)
    @JvmField val I_PPP = h(I, P, P, P)
    @JvmField val I_PPPP = h(I, P, P, P, P)
    @JvmField val I_PIJP = h(I, P, I, J, P)
    @JvmField val I_PJP = h(I, P, J, P)
    @JvmField val I_PJPP = h(I, P, J, P, P)
    @JvmField val I_PJJJ = h(I, P, J, J, J)
    @JvmField val I_PJJJIP = h(I, P, J, J, J, I, P)
    @JvmField val I_PJIPPP = h(I, P, J, I, P, P, P)
    @JvmField val I_PJJJJP = h(I, P, J, J, J, J, P)
    @JvmField val I_PIPJ = h(I, P, I, P, J)
    @JvmField val V_P = h(null, P)
    @JvmField val V_PP = h(null, P, P)
    @JvmField val V_PPP = h(null, P, P, P)
    @JvmField val V_PIP = h(null, P, I, P)
    @JvmField val V_PJ = h(null, P, J)
    @JvmField val V_PJP = h(null, P, J, P)
    @JvmField val V_PIIP = h(null, P, I, I, P)
    @JvmField val V_PPI = h(null, P, P, I)
    @JvmField val V_PIJ = h(null, P, I, J)
    @JvmField val V_PJIIIP = h(null, P, J, I, I, I, P)
    @JvmField val V_PIIPP = h(null, P, I, I, P, P)
    @JvmField val V_PIIII = h(null, P, I, I, I, I)
    @JvmField val V_PJIJIP = h(null, P, J, I, J, I, P)
    @JvmField val V_PIIIIPIPIP = h(null, P, I, I, I, I, P, I, P, I, P)
    @JvmField val V_PJIJIIPI = h(null, P, J, I, J, I, I, P, I)
}

private val NULL: MemorySegment = MemorySegment.NULL

/** Raised when a Vulkan call does not return VK_SUCCESS. */
class VulkanException(val operation: String, val result: Int) : RuntimeException("$operation: VkResult=$result")

internal fun Int.vkCheck(operation: String): Int {
    if (this != VK_SUCCESS) throw VulkanException(operation, this)
    return this
}

/** The loader library and the functions available before an instance exists. */
internal object VkLoader {
    private val library: SymbolLookup by lazy {
        val names = listOf("libvulkan.so.1", "libvulkan.so")
        names.firstNotNullOfOrNull { n -> runCatching { SymbolLookup.libraryLookup(n, Arena.global()) }.getOrNull() }
            ?: throw IllegalStateException("No Vulkan loader found (${names.joinToString()}). Install vulkan-icd-loader.")
    }
    private val getInstanceProcAddr: MemorySegment by lazy {
        library.find("vkGetInstanceProcAddr").orElseThrow { IllegalStateException("vkGetInstanceProcAddr missing from the Vulkan loader") }
    }

    fun instanceProc(instance: MemorySegment, name: String): MemorySegment = Arena.ofConfined().use { a ->
        Sig.P_PP.invokeExact(getInstanceProcAddr, instance, a.allocateFrom(name)) as MemorySegment
    }

    private fun global(name: String) = instanceProc(NULL, name).also { require(it != NULL) { "$name missing" } }
    private val createInstance by lazy { global("vkCreateInstance") }
    private val enumerateLayers by lazy { global("vkEnumerateInstanceLayerProperties") }
    private val enumerateExtensions by lazy { global("vkEnumerateInstanceExtensionProperties") }

    fun createInstance(info: MemorySegment, out: MemorySegment) =
        (Sig.I_PPP.invokeExact(createInstance, info, NULL, out) as Int).vkCheck("vkCreateInstance")

    /** Names of the instance layers present on this machine. */
    fun layerNames(): List<String> = Arena.ofConfined().use { a ->
        val n = a.allocate(JAVA_INT)
        (Sig.I_PP.invokeExact(enumerateLayers, n, NULL) as Int).vkCheck("vkEnumerateInstanceLayerProperties")
        val count = n.get(JAVA_INT, 0)
        val props = a.structs(VkLayerProperties, count)
        (Sig.I_PP.invokeExact(enumerateLayers, n, props) as Int).vkCheck("vkEnumerateInstanceLayerProperties")
        List(n.get(JAVA_INT, 0)) { props.at(VkLayerProperties, it).chars(VkLayerProperties, "layerName") }
    }

    /** Names of the instance extensions the loader and drivers offer. */
    fun extensionNames(): List<String> = Arena.ofConfined().use { a ->
        val n = a.allocate(JAVA_INT)
        (Sig.I_PPP.invokeExact(enumerateExtensions, NULL, n, NULL) as Int).vkCheck("vkEnumerateInstanceExtensionProperties")
        val count = n.get(JAVA_INT, 0)
        val props = a.structs(VkExtensionProperties, count)
        (Sig.I_PPP.invokeExact(enumerateExtensions, NULL, n, props) as Int).vkCheck("vkEnumerateInstanceExtensionProperties")
        List(n.get(JAVA_INT, 0)) { props.at(VkExtensionProperties, it).chars(VkExtensionProperties, "extensionName") }
    }
}

/** Instance-level functions, resolved once for [handle]. Optional extension functions may be NULL. */
internal class VkInstanceApi(val handle: MemorySegment) {
    private fun proc(name: String) = VkLoader.instanceProc(handle, name).also { require(it != NULL) { "$name missing" } }
    private fun optional(name: String) = VkLoader.instanceProc(handle, name)

    private val pDestroyInstance = proc("vkDestroyInstance")
    private val pEnumeratePhysicalDevices = proc("vkEnumeratePhysicalDevices")
    private val pGetPhysicalDeviceQueueFamilyProperties = proc("vkGetPhysicalDeviceQueueFamilyProperties")
    private val pGetPhysicalDeviceProperties = proc("vkGetPhysicalDeviceProperties")
    private val pGetPhysicalDeviceFormatProperties = proc("vkGetPhysicalDeviceFormatProperties")
    private val pGetPhysicalDeviceMemoryProperties = proc("vkGetPhysicalDeviceMemoryProperties")
    private val pCreateDevice = proc("vkCreateDevice")
    private val pGetDeviceProcAddr = proc("vkGetDeviceProcAddr")
    private val pGetPhysicalDeviceSurfaceSupportKHR = optional("vkGetPhysicalDeviceSurfaceSupportKHR")
    private val pGetPhysicalDeviceSurfaceCapabilitiesKHR = optional("vkGetPhysicalDeviceSurfaceCapabilitiesKHR")
    private val pGetPhysicalDeviceSurfaceFormatsKHR = optional("vkGetPhysicalDeviceSurfaceFormatsKHR")
    private val pDestroySurfaceKHR = optional("vkDestroySurfaceKHR")
    private val pCreateWaylandSurfaceKHR = optional("vkCreateWaylandSurfaceKHR")
    private val pCreateXcbSurfaceKHR = optional("vkCreateXcbSurfaceKHR")
    private val pCreateDebugUtilsMessengerEXT = optional("vkCreateDebugUtilsMessengerEXT")
    private val pDestroyDebugUtilsMessengerEXT = optional("vkDestroyDebugUtilsMessengerEXT")

    private fun need(p: MemorySegment, name: String) = p.also { require(it != NULL) { "$name unavailable: extension not enabled" } }

    fun destroyInstance() { Sig.V_PP.invokeExact(pDestroyInstance, handle, NULL) }
    fun enumeratePhysicalDevices(count: MemorySegment, devices: MemorySegment) =
        (Sig.I_PPP.invokeExact(pEnumeratePhysicalDevices, handle, count, devices) as Int).vkCheck("vkEnumeratePhysicalDevices")
    fun getPhysicalDeviceQueueFamilyProperties(pd: MemorySegment, count: MemorySegment, props: MemorySegment) {
        Sig.V_PPP.invokeExact(pGetPhysicalDeviceQueueFamilyProperties, pd, count, props) }
    fun getPhysicalDeviceProperties(pd: MemorySegment, props: MemorySegment) {
        Sig.V_PP.invokeExact(pGetPhysicalDeviceProperties, pd, props) }
    fun getPhysicalDeviceFormatProperties(pd: MemorySegment, format: Int, props: MemorySegment) {
        Sig.V_PIP.invokeExact(pGetPhysicalDeviceFormatProperties, pd, format, props) }
    fun getPhysicalDeviceMemoryProperties(pd: MemorySegment, props: MemorySegment) {
        Sig.V_PP.invokeExact(pGetPhysicalDeviceMemoryProperties, pd, props) }
    fun createDevice(pd: MemorySegment, info: MemorySegment, out: MemorySegment) =
        (Sig.I_PPPP.invokeExact(pCreateDevice, pd, info, NULL, out) as Int).vkCheck("vkCreateDevice")
    fun deviceProc(device: MemorySegment, name: String): MemorySegment = Arena.ofConfined().use { a ->
        Sig.P_PP.invokeExact(pGetDeviceProcAddr, device, a.allocateFrom(name)) as MemorySegment
    }

    fun getPhysicalDeviceSurfaceSupportKHR(pd: MemorySegment, family: Int, surface: Long, out: MemorySegment) =
        (Sig.I_PIJP.invokeExact(need(pGetPhysicalDeviceSurfaceSupportKHR, "vkGetPhysicalDeviceSurfaceSupportKHR"), pd, family, surface, out) as Int)
            .vkCheck("vkGetPhysicalDeviceSurfaceSupportKHR")
    fun getPhysicalDeviceSurfaceCapabilitiesKHR(pd: MemorySegment, surface: Long, caps: MemorySegment) =
        (Sig.I_PJP.invokeExact(need(pGetPhysicalDeviceSurfaceCapabilitiesKHR, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR"), pd, surface, caps) as Int)
            .vkCheck("vkGetPhysicalDeviceSurfaceCapabilitiesKHR")
    fun getPhysicalDeviceSurfaceFormatsKHR(pd: MemorySegment, surface: Long, count: MemorySegment, formats: MemorySegment) =
        (Sig.I_PJPP.invokeExact(need(pGetPhysicalDeviceSurfaceFormatsKHR, "vkGetPhysicalDeviceSurfaceFormatsKHR"), pd, surface, count, formats) as Int)
            .vkCheck("vkGetPhysicalDeviceSurfaceFormatsKHR")
    fun destroySurfaceKHR(surface: Long) {
        Sig.V_PJP.invokeExact(need(pDestroySurfaceKHR, "vkDestroySurfaceKHR"), handle, surface, NULL) }
    fun createWaylandSurfaceKHR(info: MemorySegment, out: MemorySegment) =
        (Sig.I_PPPP.invokeExact(need(pCreateWaylandSurfaceKHR, "vkCreateWaylandSurfaceKHR"), handle, info, NULL, out) as Int)
            .vkCheck("vkCreateWaylandSurfaceKHR")
    fun createXcbSurfaceKHR(info: MemorySegment, out: MemorySegment) =
        (Sig.I_PPPP.invokeExact(need(pCreateXcbSurfaceKHR, "vkCreateXcbSurfaceKHR"), handle, info, NULL, out) as Int)
            .vkCheck("vkCreateXcbSurfaceKHR")
    fun createDebugUtilsMessengerEXT(info: MemorySegment, out: MemorySegment) =
        (Sig.I_PPPP.invokeExact(need(pCreateDebugUtilsMessengerEXT, "vkCreateDebugUtilsMessengerEXT"), handle, info, NULL, out) as Int)
            .vkCheck("vkCreateDebugUtilsMessengerEXT")
    fun destroyDebugUtilsMessengerEXT(messenger: Long) {
        Sig.V_PJP.invokeExact(need(pDestroyDebugUtilsMessengerEXT, "vkDestroyDebugUtilsMessengerEXT"), handle, messenger, NULL) }
}

/** Device-level functions, resolved with vkGetDeviceProcAddr for [handle]. */
internal class VkDeviceApi(val handle: MemorySegment, instance: VkInstanceApi, swapchain: Boolean) {
    private val proc = { name: String -> instance.deviceProc(handle, name).also { require(it != NULL) { "$name missing" } } }
    private val optional = { name: String -> if (swapchain) proc(name) else NULL }

    private val pDestroyDevice = proc("vkDestroyDevice")
    private val pGetDeviceQueue = proc("vkGetDeviceQueue")
    private val pDeviceWaitIdle = proc("vkDeviceWaitIdle")
    private val pCreateCommandPool = proc("vkCreateCommandPool")
    private val pDestroyCommandPool = proc("vkDestroyCommandPool")
    private val pAllocateCommandBuffers = proc("vkAllocateCommandBuffers")
    private val pCreateSemaphore = proc("vkCreateSemaphore")
    private val pDestroySemaphore = proc("vkDestroySemaphore")
    private val pCreateBuffer = proc("vkCreateBuffer")
    private val pDestroyBuffer = proc("vkDestroyBuffer")
    private val pGetBufferMemoryRequirements = proc("vkGetBufferMemoryRequirements")
    private val pAllocateMemory = proc("vkAllocateMemory")
    private val pFreeMemory = proc("vkFreeMemory")
    private val pBindBufferMemory = proc("vkBindBufferMemory")
    private val pMapMemory = proc("vkMapMemory")
    private val pUnmapMemory = proc("vkUnmapMemory")
    private val pCreateImage = proc("vkCreateImage")
    private val pDestroyImage = proc("vkDestroyImage")
    private val pGetImageMemoryRequirements = proc("vkGetImageMemoryRequirements")
    private val pBindImageMemory = proc("vkBindImageMemory")
    private val pCreateImageView = proc("vkCreateImageView")
    private val pDestroyImageView = proc("vkDestroyImageView")
    private val pCreateRenderPass = proc("vkCreateRenderPass")
    private val pDestroyRenderPass = proc("vkDestroyRenderPass")
    private val pCreateFramebuffer = proc("vkCreateFramebuffer")
    private val pDestroyFramebuffer = proc("vkDestroyFramebuffer")
    private val pCreateShaderModule = proc("vkCreateShaderModule")
    private val pDestroyShaderModule = proc("vkDestroyShaderModule")
    private val pCreatePipelineLayout = proc("vkCreatePipelineLayout")
    private val pDestroyPipelineLayout = proc("vkDestroyPipelineLayout")
    private val pCreateGraphicsPipelines = proc("vkCreateGraphicsPipelines")
    private val pDestroyPipeline = proc("vkDestroyPipeline")
    private val pQueueSubmit = proc("vkQueueSubmit")
    private val pQueueWaitIdle = proc("vkQueueWaitIdle")
    private val pResetCommandBuffer = proc("vkResetCommandBuffer")
    private val pBeginCommandBuffer = proc("vkBeginCommandBuffer")
    private val pEndCommandBuffer = proc("vkEndCommandBuffer")
    private val pCmdBeginRenderPass = proc("vkCmdBeginRenderPass")
    private val pCmdEndRenderPass = proc("vkCmdEndRenderPass")
    private val pCmdBindPipeline = proc("vkCmdBindPipeline")
    private val pCmdPushConstants = proc("vkCmdPushConstants")
    private val pCmdBindVertexBuffers = proc("vkCmdBindVertexBuffers")
    private val pCmdDraw = proc("vkCmdDraw")
    private val pCmdCopyImageToBuffer = proc("vkCmdCopyImageToBuffer")
    private val pCmdPipelineBarrier = proc("vkCmdPipelineBarrier")
    private val pCmdBlitImage = proc("vkCmdBlitImage")
    private val pCmdSetViewport = proc("vkCmdSetViewport")
    private val pCmdSetScissor = proc("vkCmdSetScissor")
    private val pCreateSwapchainKHR = optional("vkCreateSwapchainKHR")
    private val pDestroySwapchainKHR = optional("vkDestroySwapchainKHR")
    private val pGetSwapchainImagesKHR = optional("vkGetSwapchainImagesKHR")
    private val pAcquireNextImageKHR = optional("vkAcquireNextImageKHR")
    private val pQueuePresentKHR = optional("vkQueuePresentKHR")

    fun destroyDevice() { Sig.V_PP.invokeExact(pDestroyDevice, handle, NULL) }
    fun getDeviceQueue(family: Int, index: Int, out: MemorySegment) { Sig.V_PIIP.invokeExact(pGetDeviceQueue, handle, family, index, out) }
    fun deviceWaitIdle() = (Sig.I_P.invokeExact(pDeviceWaitIdle, handle) as Int).vkCheck("vkDeviceWaitIdle")

    private fun create(p: MemorySegment, name: String, info: MemorySegment, out: MemorySegment) =
        (Sig.I_PPPP.invokeExact(p, handle, info, NULL, out) as Int).vkCheck(name)
    private fun destroy(p: MemorySegment, h: Long) { if (h != 0L) Sig.V_PJP.invokeExact(p, handle, h, NULL) }

    fun createCommandPool(info: MemorySegment, out: MemorySegment) = create(pCreateCommandPool, "vkCreateCommandPool", info, out)
    fun destroyCommandPool(h: Long) = destroy(pDestroyCommandPool, h)
    fun allocateCommandBuffers(info: MemorySegment, out: MemorySegment) =
        (Sig.I_PPP.invokeExact(pAllocateCommandBuffers, handle, info, out) as Int).vkCheck("vkAllocateCommandBuffers")
    fun createSemaphore(info: MemorySegment, out: MemorySegment) = create(pCreateSemaphore, "vkCreateSemaphore", info, out)
    fun destroySemaphore(h: Long) = destroy(pDestroySemaphore, h)
    fun createBuffer(info: MemorySegment, out: MemorySegment) = create(pCreateBuffer, "vkCreateBuffer", info, out)
    fun destroyBuffer(h: Long) = destroy(pDestroyBuffer, h)
    fun getBufferMemoryRequirements(buffer: Long, out: MemorySegment) { Sig.V_PJP.invokeExact(pGetBufferMemoryRequirements, handle, buffer, out) }
    fun allocateMemory(info: MemorySegment, out: MemorySegment) = create(pAllocateMemory, "vkAllocateMemory", info, out)
    fun freeMemory(h: Long) = destroy(pFreeMemory, h)
    fun bindBufferMemory(buffer: Long, memory: Long, offset: Long) =
        (Sig.I_PJJJ.invokeExact(pBindBufferMemory, handle, buffer, memory, offset) as Int).vkCheck("vkBindBufferMemory")
    fun mapMemory(memory: Long, offset: Long, size: Long, out: MemorySegment) =
        (Sig.I_PJJJIP.invokeExact(pMapMemory, handle, memory, offset, size, 0, out) as Int).vkCheck("vkMapMemory")
    fun unmapMemory(memory: Long) { Sig.V_PJ.invokeExact(pUnmapMemory, handle, memory) }
    fun createImage(info: MemorySegment, out: MemorySegment) = create(pCreateImage, "vkCreateImage", info, out)
    fun destroyImage(h: Long) = destroy(pDestroyImage, h)
    fun getImageMemoryRequirements(image: Long, out: MemorySegment) { Sig.V_PJP.invokeExact(pGetImageMemoryRequirements, handle, image, out) }
    fun bindImageMemory(image: Long, memory: Long, offset: Long) =
        (Sig.I_PJJJ.invokeExact(pBindImageMemory, handle, image, memory, offset) as Int).vkCheck("vkBindImageMemory")
    fun createImageView(info: MemorySegment, out: MemorySegment) = create(pCreateImageView, "vkCreateImageView", info, out)
    fun destroyImageView(h: Long) = destroy(pDestroyImageView, h)
    fun createRenderPass(info: MemorySegment, out: MemorySegment) = create(pCreateRenderPass, "vkCreateRenderPass", info, out)
    fun destroyRenderPass(h: Long) = destroy(pDestroyRenderPass, h)
    fun createFramebuffer(info: MemorySegment, out: MemorySegment) = create(pCreateFramebuffer, "vkCreateFramebuffer", info, out)
    fun destroyFramebuffer(h: Long) = destroy(pDestroyFramebuffer, h)
    fun createShaderModule(info: MemorySegment, out: MemorySegment) = create(pCreateShaderModule, "vkCreateShaderModule", info, out)
    fun destroyShaderModule(h: Long) = destroy(pDestroyShaderModule, h)
    fun createPipelineLayout(info: MemorySegment, out: MemorySegment) = create(pCreatePipelineLayout, "vkCreatePipelineLayout", info, out)
    fun destroyPipelineLayout(h: Long) = destroy(pDestroyPipelineLayout, h)
    fun createGraphicsPipelines(count: Int, infos: MemorySegment, out: MemorySegment) =
        (Sig.I_PJIPPP.invokeExact(pCreateGraphicsPipelines, handle, VK_NULL_HANDLE, count, infos, NULL, out) as Int).vkCheck("vkCreateGraphicsPipelines")
    fun destroyPipeline(h: Long) = destroy(pDestroyPipeline, h)

    fun queueSubmit(queue: MemorySegment, count: Int, submits: MemorySegment, fence: Long) =
        (Sig.I_PIPJ.invokeExact(pQueueSubmit, queue, count, submits, fence) as Int).vkCheck("vkQueueSubmit")
    fun queueWaitIdle(queue: MemorySegment) = (Sig.I_P.invokeExact(pQueueWaitIdle, queue) as Int).vkCheck("vkQueueWaitIdle")

    fun resetCommandBuffer(cmd: MemorySegment, flags: Int) =
        (Sig.I_PI.invokeExact(pResetCommandBuffer, cmd, flags) as Int).vkCheck("vkResetCommandBuffer")
    fun beginCommandBuffer(cmd: MemorySegment, info: MemorySegment) =
        (Sig.I_PP.invokeExact(pBeginCommandBuffer, cmd, info) as Int).vkCheck("vkBeginCommandBuffer")
    fun endCommandBuffer(cmd: MemorySegment) = (Sig.I_P.invokeExact(pEndCommandBuffer, cmd) as Int).vkCheck("vkEndCommandBuffer")
    fun cmdBeginRenderPass(cmd: MemorySegment, info: MemorySegment, contents: Int) { Sig.V_PPI.invokeExact(pCmdBeginRenderPass, cmd, info, contents) }
    fun cmdEndRenderPass(cmd: MemorySegment) { Sig.V_P.invokeExact(pCmdEndRenderPass, cmd) }
    fun cmdBindPipeline(cmd: MemorySegment, bindPoint: Int, pipeline: Long) { Sig.V_PIJ.invokeExact(pCmdBindPipeline, cmd, bindPoint, pipeline) }
    fun cmdPushConstants(cmd: MemorySegment, layout: Long, stages: Int, offset: Int, size: Int, values: MemorySegment) {
        Sig.V_PJIIIP.invokeExact(pCmdPushConstants, cmd, layout, stages, offset, size, values) }
    fun cmdBindVertexBuffers(cmd: MemorySegment, first: Int, count: Int, buffers: MemorySegment, offsets: MemorySegment) {
        Sig.V_PIIPP.invokeExact(pCmdBindVertexBuffers, cmd, first, count, buffers, offsets) }
    fun cmdDraw(cmd: MemorySegment, vertices: Int, instances: Int, firstVertex: Int, firstInstance: Int) {
        Sig.V_PIIII.invokeExact(pCmdDraw, cmd, vertices, instances, firstVertex, firstInstance) }
    fun cmdCopyImageToBuffer(cmd: MemorySegment, image: Long, layout: Int, buffer: Long, count: Int, regions: MemorySegment) {
        Sig.V_PJIJIP.invokeExact(pCmdCopyImageToBuffer, cmd, image, layout, buffer, count, regions) }
    fun cmdPipelineBarrier(cmd: MemorySegment, srcStage: Int, dstStage: Int, dependencyFlags: Int,
                           memoryCount: Int, memory: MemorySegment, bufferCount: Int, buffers: MemorySegment,
                           imageCount: Int, images: MemorySegment) {
        Sig.V_PIIIIPIPIP.invokeExact(pCmdPipelineBarrier, cmd, srcStage, dstStage, dependencyFlags,
            memoryCount, memory, bufferCount, buffers, imageCount, images) }
    fun cmdBlitImage(cmd: MemorySegment, src: Long, srcLayout: Int, dst: Long, dstLayout: Int, count: Int, regions: MemorySegment, filter: Int) {
        Sig.V_PJIJIIPI.invokeExact(pCmdBlitImage, cmd, src, srcLayout, dst, dstLayout, count, regions, filter) }

    fun cmdSetViewport(cmd: MemorySegment, viewports: MemorySegment) { Sig.V_PIIP.invokeExact(pCmdSetViewport, cmd, 0, 1, viewports) }
    fun cmdSetScissor(cmd: MemorySegment, scissors: MemorySegment) { Sig.V_PIIP.invokeExact(pCmdSetScissor, cmd, 0, 1, scissors) }

    fun createSwapchainKHR(info: MemorySegment, out: MemorySegment) = create(pCreateSwapchainKHR, "vkCreateSwapchainKHR", info, out)
    fun destroySwapchainKHR(h: Long) = destroy(pDestroySwapchainKHR, h)
    fun getSwapchainImagesKHR(swapchain: Long, count: MemorySegment, images: MemorySegment) =
        (Sig.I_PJPP.invokeExact(pGetSwapchainImagesKHR, handle, swapchain, count, images) as Int).vkCheck("vkGetSwapchainImagesKHR")
    /** Returns the raw VkResult: VK_SUBOPTIMAL_KHR and VK_ERROR_OUT_OF_DATE_KHR are for the caller to handle. */
    fun acquireNextImageKHR(swapchain: Long, timeout: Long, semaphore: Long, fence: Long, index: MemorySegment): Int =
        Sig.I_PJJJJP.invokeExact(pAcquireNextImageKHR, handle, swapchain, timeout, semaphore, fence, index) as Int
    /** Returns the raw VkResult, like [acquireNextImageKHR]. */
    fun queuePresentKHR(queue: MemorySegment, info: MemorySegment): Int = Sig.I_PP.invokeExact(pQueuePresentKHR, queue, info) as Int
}
