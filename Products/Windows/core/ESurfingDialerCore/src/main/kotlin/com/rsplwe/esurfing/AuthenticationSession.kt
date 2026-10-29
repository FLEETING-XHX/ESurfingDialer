package com.rsplwe.esurfing

/** Session boundary; production uses the unchanged Windows Android64 native provider. */
interface AuthenticationSession {
    fun encrypt(value: String): String
    fun decrypt(value: String): String
    fun free()
}
