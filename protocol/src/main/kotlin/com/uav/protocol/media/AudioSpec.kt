package com.uav.protocol.media

/**
 * 音频流的固定参数（计划 10.13）。
 *
 * 为什么把这些数字集中到一处：采样率 / 声道数 / 每帧样本数**同时**出现在
 * 四个地方 —— 手机端 `AudioRecord`、手机端 AAC 编码器、宿主端 AAC 解码器、
 * 宿主端 `AudioTrack`。任何一处不一致都不会报错，只会表现为
 * "有声音但音调不对""只有一边声道""播放卡顿"这类**很难从日志定位**的症状。
 * 集中成常量并用单测钉住，比四处各写一遍安全得多。
 *
 * ⚠️ 与视频不同，音频**不参与尺寸协商**：48 kHz 立体声是 Android 内录
 * （playback capture）最常见的输出格式，AAC-LC 在该组合下通用可解码。
 * `OPEN` 的 `max_size` 只作用于视频。
 */
object AudioSpec {

    /** 采样率（Hz）。48 kHz 是内录的常见默认，也是 AAC-LC 的标准档位。 */
    const val SAMPLE_RATE = 48_000

    /** 声道数。内录拿到的通常是混音后的立体声。 */
    const val CHANNELS = 2

    /** PCM 位深。内录的唯一可选格式就是 16 bit（`ENCODING_PCM_16BIT`）。 */
    const val BITS_PER_SAMPLE = 16

    const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8

    /**
     * 单个 AAC 帧的样本数（**每声道**）。AAC-LC 的帧长固定为 1024 样本。
     *
     * 读取单位取它而不是随便一个数：`MediaCodec` 的 AAC 编码器内部按 1024 样本
     * 切帧，喂整帧可以让"一次读取 = 一次入队"，不多一层缓冲。
     */
    const val AAC_FRAME_SAMPLES = 1024

    /** 一次读取的 PCM 字节数（= 1024 样本 × 声道 × 2B）。 */
    const val PCM_FRAME_BYTES = AAC_FRAME_SAMPLES * CHANNELS * BYTES_PER_SAMPLE

    /** 每秒 PCM 字节数，用于估算音频带宽与缓冲深度。 */
    const val PCM_BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE

    /** 默认音频码率（AAC-LC 128 kbps 立体声，透明感足够、带宽占用小）。 */
    const val DEFAULT_BIT_RATE = 128_000

    /** 音频编码器 MIME（纯字符串，本模块不依赖 `android.*`）。 */
    const val MIME = "audio/mp4a-latm"

    /** `MediaCodecInfo.CodecProfileLevel.AACObjectLC` —— 免引 `android.*`，故写常量。 */
    const val AAC_PROFILE_LC = 2

    /** 单个 AAC 帧的标称时长（微秒），= 1024 / 48000 s。 */
    val FRAME_DURATION_US: Long = AAC_FRAME_SAMPLES * 1_000_000L / SAMPLE_RATE

    /** 允许的采样率范围（校验流头时用；覆盖 8k 电话音质到 192k 高解析）。 */
    val SAMPLE_RATE_RANGE = 8_000..192_000

    /** 允许的声道数范围。 */
    val CHANNELS_RANGE = 1..8

    /** 本协议音频流使用的 `codecId`。 */
    const val CODEC_ID = CodecId.AAC
}
