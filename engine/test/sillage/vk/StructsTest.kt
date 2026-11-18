package sillage.vk

import kotlin.test.Test
import kotlin.test.assertEquals

/** Layouts of the structs added for the shadow map, as gcc lays them out on LP64 Linux (x86-64, AArch64). */
class StructsTest {
    private fun check(s: Struct, size: Long, vararg offsets: Pair<String, Long>) {
        assertEquals(size, s.size, "${s.name}: size")
        for ((field, offset) in offsets) assertEquals(offset, s.offset(field), "${s.name}.$field")
    }

    @Test fun lesStructuresDesDescripteursSontCellesDuC() {
        check(VkSamplerCreateInfo, 80, "compareOp" to 60, "borderColor" to 72, "unnormalizedCoordinates" to 76)
        check(VkDescriptorSetLayoutBinding, 24, "pImmutableSamplers" to 16)
        check(VkDescriptorSetLayoutCreateInfo, 32, "pBindings" to 24)
        check(VkDescriptorPoolSize, 8)
        check(VkDescriptorPoolCreateInfo, 40, "pPoolSizes" to 32)
        check(VkDescriptorSetAllocateInfo, 40, "descriptorPool" to 16, "pSetLayouts" to 32)
        check(VkDescriptorImageInfo, 24, "imageLayout" to 16)
        check(VkDescriptorBufferInfo, 24, "offset" to 8, "range" to 16)
        check(VkWriteDescriptorSet, 64, "pBufferInfo" to 48, "dstSet" to 16, "descriptorType" to 36, "pImageInfo" to 40, "pTexelBufferView" to 56)
    }
}
