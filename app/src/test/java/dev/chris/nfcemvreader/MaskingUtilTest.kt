package dev.chris.nfcemvreader

import org.junit.Assert.assertEquals
import org.junit.Test

class MaskingUtilTest {

    @Test
    fun panMasking_isCorrect_StandardLength() {
        // Standard 16-digit PAN
        val original = "4111111111111111"
        val expected = "411111******1111"
        assertEquals(expected, EMVParser.maskPan(original))
    }

    @Test
    fun panMasking_isCorrect_Padded() {
        // A PAN with an 'F' pad byte
        val original = "5413331234567890F"
        val expected = "541333******7890"
        assertEquals(expected, EMVParser.maskPan(original))
    }

    @Test
    fun panMasking_isCorrect_ShortLength() {
        // A 13-digit PAN
        val original = "4123456789012"
        val expected = "412345***9012"
        assertEquals(expected, EMVParser.maskPan(original))
    }

    @Test
    fun panMasking_isTooShort() {
        // Too short to mask
        val original = "1234567890"
        val expected = "PAN_TOO_SHORT"
        assertEquals(expected, EMVParser.maskPan(original))
    }
}