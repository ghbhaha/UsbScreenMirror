package com.uav.protocol

/**
 * 协议错误。
 *
 * 凡是「报文头不合法 / magic 或 checksum 不符 / 长度超限 / 命令被裁剪」一律抛本异常。
 * 计划 1.2 明确要求：**不静默跳过**，否则错位会被掩盖成"间歇性断流"这类难查现象。
 */
class ProtocolException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)
