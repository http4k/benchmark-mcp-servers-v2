package com.benchmark.mcp.tools

import com.benchmark.mcp.AppJson
import org.http4k.ai.mcp.ToolResponse.Ok
import org.http4k.ai.mcp.model.Content.Text

fun Any.toToolResponse() = Ok(Text(AppJson.asFormatString(this)))
