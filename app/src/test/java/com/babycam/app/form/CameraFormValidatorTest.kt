package com.babycam.app.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFormValidatorTest {

    private val validator = CameraFormValidator()

    private fun validate(
        host: String = "192.168.1.1",
        portText: String = "554",
        path: String = "",
        username: String = "",
        password: String = "",
    ) = validator.validate(host, portText, path, username, password)

    @Test
    fun `empty host produces an error on the host field only`() {
        val result = validate(host = "") as ValidationResult.Invalid

        assertEquals(setOf(FormField.HOST), result.errors.keys)
    }

    @Test
    fun `empty port produces an error on the port field`() {
        val result = validate(portText = "") as ValidationResult.Invalid

        assertTrue(result.errors.containsKey(FormField.PORT))
    }

    @Test
    fun `non-numeric port produces an error on the port field`() {
        val result = validate(portText = "abc") as ValidationResult.Invalid

        assertTrue(result.errors.containsKey(FormField.PORT))
    }

    @Test
    fun `port 0 is out of range and produces an error`() {
        val result = validate(portText = "0") as ValidationResult.Invalid

        assertTrue(result.errors.containsKey(FormField.PORT))
    }

    @Test
    fun `port 65536 is out of range and produces an error`() {
        val result = validate(portText = "65536") as ValidationResult.Invalid

        assertTrue(result.errors.containsKey(FormField.PORT))
    }

    @Test
    fun `port 1 is a valid boundary value`() {
        val result = validate(portText = "1")

        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `port 65535 is a valid boundary value`() {
        val result = validate(portText = "65535")

        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `valid host and port with blank optional fields is a success`() {
        val result = validate(host = "camera.local", portText = "554", path = "", username = "", password = "")

        assertTrue(result is ValidationResult.Valid)
    }
}
