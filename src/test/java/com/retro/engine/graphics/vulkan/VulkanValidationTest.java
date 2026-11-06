package com.retro.engine.graphics.vulkan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VulkanValidationTest {

    @Test
    void onlyTheMoltenVkIntegerBlendMessageIsIgnored() {
        assertTrue(VulkanValidation.isKnownFalsePositive(
                "VK_ERROR_FEATURE_NOT_PRESENT: Blending is enabled for attachment with format VK_FORMAT_R8G8B8A8_UINT, which does not support it."));
        assertFalse(VulkanValidation.isKnownFalsePositive(
                "VK_ERROR_FEATURE_NOT_PRESENT: Blending is enabled for attachment with format VK_FORMAT_R32_UINT, which does not support it."));
        assertFalse(VulkanValidation.isKnownFalsePositive("vkUpdateDescriptorSets(): pDescriptorWrites[2] (portability error)"));
        assertFalse(VulkanValidation.isKnownFalsePositive(null));
    }
}
