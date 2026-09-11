package com.tracktosearch.data.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 守住「打包的模型文件与 sherpa-onnx 运行时期望对得上」这条线。
 *
 * sherpa-onnx 用编码器元数据里的 model_type 决定实例化哪套 OnlineTransducerModel，
 * 而该类实现要求同一份元数据里带一组**因结构而异**的键：zipformer2 导出写的是
 * query_head_dims / value_head_dims / num_heads，老 zipformer 写的是 attention_dims。
 * 键对不上时它不抛异常，而是在 native 里 SHERPA_ONNX_EXIT(-1)：进程当场退出，
 * Java 侧 catch(Throwable) 拦不住，表现为用户按住录音那一刻 App 闪退。
 *
 * 所以模型一换就该在这里炸，而不是在真机的手指底下炸。
 */
class AiKwsModelAssetsTest {

    private val kwsDir = File("src/main/assets/kws")

    @Test
    fun encoder_metadataKeysCoverEverythingItsModelTypeNeeds() {
        val metadata = readOnnxMetadata(File(kwsDir, "encoder-int8.onnx"))
        val modelType = metadata["model_type"]

        assertThat(modelType).isNotNull()
        val required = when (modelType) {
            // 各实现读的键见 sherpa-onnx/csrc/online-zipformer2-transducer-model.cc 与
            // online-zipformer-transducer-model.cc 的 InitEncoder
            "zipformer2" -> listOf(
                "encoder_dims", "query_head_dims", "value_head_dims", "num_heads",
                "num_encoder_layers", "cnn_module_kernels", "left_context_len",
                "T", "decode_chunk_len"
            )
            "zipformer" -> listOf(
                "encoder_dims", "attention_dims",
                "num_encoder_layers", "cnn_module_kernels", "left_context_len",
                "T", "decode_chunk_len"
            )
            else -> throw AssertionError(
                "模型自报的 model_type=$modelType 没有对应的运行时实现，" +
                    "sherpa-onnx 认不出来时拿到的模型是 null，native 侧照样崩"
            )
        }
        required.forEach { key ->
            assertThat(metadata).containsKey(key)
        }
    }

    @Test
    fun encoder_perLayerMetadataStaysInStepWithLayerCount() {
        val metadata = readOnnxMetadata(File(kwsDir, "encoder-int8.onnx"))

        // 逐层参数必须和层数一一对应：少一项就是越界读，native 不会报错，只会算出垃圾
        val layers = metadata.getValue("num_encoder_layers").split(',').size
        listOf("encoder_dims", "cnn_module_kernels", "left_context_len")
            .forEach { key ->
                assertThat(metadata.getValue(key).split(',').size).isEqualTo(layers)
            }
    }

    /**
     * 读 ONNX ModelProto 尾部的 metadata_props（field 14）。
     *
     * 只认单字节长度前缀：本项目的元数据值都在 128 字节以内，超长的原样跳过，
     * 不为了通用性把测试写成半个 protobuf 解析器。
     */
    private fun readOnnxMetadata(file: File): Map<String, String> {
        val bytes = file.readBytes()
        val metadata = mutableMapOf<String, String>()
        var index = 0
        while (index < bytes.size - 4) {
            if (bytes[index] != FIELD_METADATA_PROPS) {
                index++
                continue
            }
            val entryLength = bytes[index + 1].toInt() and 0xff
            val keyTag = index + 2
            val entryEnd = keyTag + entryLength
            if (bytes[keyTag] == FIELD_KEY && entryEnd <= bytes.size) {
                val keyLength = bytes[keyTag + 1].toInt() and 0xff
                val keyStart = keyTag + 2
                val valueTag = keyStart + keyLength
                if (valueTag + 1 < entryEnd && bytes[valueTag] == FIELD_VALUE) {
                    val valueLength = bytes[valueTag + 1].toInt() and 0xff
                    val valueStart = valueTag + 2
                    if (valueStart + valueLength <= entryEnd) {
                        val entryKey = String(bytes, keyStart, keyLength, Charsets.UTF_8)
                        metadata[entryKey] =
                            String(bytes, valueStart, valueLength, Charsets.UTF_8)
                    }
                }
            }
            index++
        }
        return metadata
    }

    private companion object {
        const val FIELD_METADATA_PROPS: Byte = 0x72
        const val FIELD_KEY: Byte = 0x0a
        const val FIELD_VALUE: Byte = 0x12
    }
}
