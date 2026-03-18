package com.benchmark.mcp

import com.squareup.moshi.JsonAdapter
import org.http4k.ai.mcp.util.ConfigurableMcpJson
import se.ansman.kotshi.KotshiJsonAdapterFactory

object AppJson : ConfigurableMcpJson(JsonJsonFactory)

@KotshiJsonAdapterFactory
object JsonJsonFactory : JsonAdapter.Factory by KotshiJsonJsonFactory