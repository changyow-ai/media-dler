package com.changyow.mediadler.util

inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
    name?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default
