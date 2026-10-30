package com.hqverse.core

/** Formatos de HQ que o aplicativo realmente lê. */
enum class ComicFormat(val label: String) {
    PDF("PDF"),
    CBZ("CBZ"),
    CBR("CBR"),
    FOLDER("Pasta de imagens");
}

/** Tipo de conteúdo real, identificado pelos primeiros bytes do arquivo. */
enum class DetectedKind { PDF, ZIP, RAR, SEVEN_ZIP, JPEG, PNG, WEBP, UNKNOWN }

object FormatDetector {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    fun isImageName(name: String): Boolean = extensionOf(name) in imageExtensions

    /** Classifica um arquivo pelo nome. Retorna null se não for candidato a HQ. */
    fun formatFromName(name: String): ComicFormat? = when (extensionOf(name)) {
        "pdf" -> ComicFormat.PDF
        "cbz" -> ComicFormat.CBZ
        "cbr" -> ComicFormat.CBR
        else -> null
    }

    /** Identifica o conteúdo real pelos bytes iniciais (mínimo recomendado: 16 bytes). */
    fun detect(header: ByteArray): DetectedKind {
        fun starts(vararg b: Int): Boolean =
            header.size >= b.size && b.indices.all { (header[it].toInt() and 0xFF) == b[it] }
        return when {
            starts(0x25, 0x50, 0x44, 0x46) -> DetectedKind.PDF // %PDF
            starts(0x50, 0x4B, 0x03, 0x04) || starts(0x50, 0x4B, 0x05, 0x06) -> DetectedKind.ZIP
            starts(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) -> DetectedKind.RAR // Rar!\x1A\x07 (RAR4 e RAR5)
            starts(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> DetectedKind.SEVEN_ZIP
            starts(0xFF, 0xD8, 0xFF) -> DetectedKind.JPEG
            starts(0x89, 0x50, 0x4E, 0x47) -> DetectedKind.PNG
            header.size >= 12 && starts(0x52, 0x49, 0x46, 0x46) &&
                header[8].toInt() == 0x57 && header[9].toInt() == 0x45 &&
                header[10].toInt() == 0x42 && header[11].toInt() == 0x50 -> DetectedKind.WEBP
            else -> DetectedKind.UNKNOWN
        }
    }

    /** RAR5 (Rar!\x1A\x07\x01\x00) é diferente do RAR4 e nem toda biblioteca o lê. */
    fun isRar5(header: ByteArray): Boolean =
        detect(header) == DetectedKind.RAR && header.size >= 8 && header[6].toInt() == 1 && header[7].toInt() == 0

    /**
     * Decide como abrir o arquivo. Muitos "CBR" são na verdade ZIP (e vice-versa), então o
     * conteúdo real tem prioridade sobre a extensão.
     */
    fun resolveReadFormat(declared: ComicFormat?, kind: DetectedKind): ComicFormat? {
        if (declared == ComicFormat.FOLDER) return ComicFormat.FOLDER
        return when (kind) {
            DetectedKind.PDF -> ComicFormat.PDF
            DetectedKind.ZIP -> ComicFormat.CBZ
            DetectedKind.RAR -> ComicFormat.CBR
            else -> null
        }
    }
}

/** Regra para pastas de imagens serem tratadas como uma HQ. */
object FolderComicRule {
    const val DEFAULT_MIN_IMAGES = 3
    fun isComicFolder(directImageCount: Int, minImages: Int = DEFAULT_MIN_IMAGES): Boolean =
        directImageCount >= minImages
}

/** Motivos de falha ao abrir/ler uma HQ, com mensagens úteis em português. */
enum class ReadFailure(val userMessage: String) {
    NOT_FOUND("O arquivo não foi encontrado. Ele pode ter sido movido ou apagado."),
    NO_PERMISSION("O aplicativo perdeu o acesso a esta pasta. Selecione a pasta novamente."),
    CORRUPT("O arquivo parece estar corrompido ou incompleto."),
    PASSWORD_PROTECTED("O arquivo é protegido por senha. Este aplicativo não abre arquivos protegidos."),
    UNSUPPORTED("Formato não suportado ou arquivo diferente do esperado."),
    RAR5_UNSUPPORTED("Este CBR usa o formato RAR5, que esta versão do leitor não consegue abrir. Converta o arquivo para CBZ (ou RAR4) e tente novamente."),
    EMPTY("Nenhuma página de imagem foi encontrada dentro do arquivo."),
    TOO_LARGE("O arquivo excede os limites de segurança (muitas páginas ou conteúdo expandido grande demais)."),
    OUT_OF_SPACE("Sem espaço de armazenamento suficiente para abrir este arquivo.");
}

class ComicReadException(val failure: ReadFailure, cause: Throwable? = null) :
    Exception(failure.userMessage, cause)
