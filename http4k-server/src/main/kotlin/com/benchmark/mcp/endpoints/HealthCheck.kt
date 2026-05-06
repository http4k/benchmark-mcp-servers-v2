package com.benchmark.mcp.endpoints

import com.benchmark.mcp.AppJson.json
import org.http4k.core.Method.GET
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.routing.bind
import se.ansman.kotshi.JsonSerializable

@JsonSerializable
data object HealthCheck {
    val status = "ok"
    val server_type = "http4k"
}

fun HealthCheck() = "/health" bind GET to { Response(OK).json(HealthCheck) }