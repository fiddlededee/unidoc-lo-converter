@file:CompilerOptions("-jvm-target", "11")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
@file:DependsOn("ai.koog:koog-agents-jvm:0.8.0")
@file:DependsOn("org.jsoup:jsoup:1.18.1")
@file:DependsOn("io.ktor:ktor-client-core:3.2.2")
@file:DependsOn("io.ktor:ktor-client-cio:3.2.2")
@file:DependsOn("io.ktor:ktor-client-content-negotiation:3.2.2")
@file:DependsOn("io.ktor:ktor-serialization-kotlinx-json:3.2.2")
@file:DependsOn("org.angryscan:core-jvm:1.5.1")
@file:OptIn(kotlin.time.ExperimentalTime::class)

import ai.koog.prompt.message.Message
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.Serializable
import org.angryscan.common.engine.kotlin.KotlinEngine
import kotlinx.serialization.json.*
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.additionalPropertiesOf
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.core.tools.schema.defaultJsonSchemaConfig
import ai.koog.agents.core.tools.schema.getJsonSchema
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.StructureFixingParser
import ai.koog.prompt.params.LLMParams
import ai.koog.serialization.typeToken
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path as KPath
import kotlin.collections.mapIndexed
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.AttributeKey
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.MainScope
import kotlinx.serialization.ExperimentalSerializationApi
import org.angryscan.common.engine.kotlin.IKotlinMatcher
import org.angryscan.common.matchers.*

System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error")
System.setProperty("org.slf4j.simpleLogger.log.ai.koog", "error")
val redactionMatchers = listOf<IKotlinMatcher>(
    Passport, SNILS, INN, OMS, OGRNIP, OKPO, Phone, Email, Address, FullName,
    CardNumber(), BankAccount, BankAccountLE, DriverLicense, VehicleRegNumber, VIN,
    CadastralNumber, OSAGOPolicy, SberBook,
    ResidencePermit, MilitaryID, LegalEntityId, LegalEntityName,
    Birthday, Certificate, EducationDoc, EducationLicense,
    ExecDocNumber, StateRegContract, Login, Geo,
    Password, CryptoWallet, CryptoSeedPhrase, CVV, HashData
)
val redactionEngine = KotlinEngine(redactionMatchers)

fun redactText(text: String): String {
    val apiKeyPatterns = listOf(Regex("""sk-or-v1-[a-fA-F0-9]{40,}"""))

    // Сначала применяем regex-паттерны для API ключей
    var result = text
    for (pattern in apiKeyPatterns) {
        val matches = pattern.findAll(result).toList().sortedByDescending { it.range.first }
        for (match in matches) {
            result = result.replaceRange(match.range, "[REDACTED:OpenRouterKey]")
        }
    }

    // Потом применяем angryscan матчеры
    val angryMatches = redactionEngine.scan(result).sortedByDescending { it.startPosition.toInt() }
    for (match in angryMatches) {
        result = result.replaceRange(
            match.startPosition.toInt()..match.endPosition.toInt(),
            "[REDACTED:${match.matcher.name}]"
        )
    }

    return result
}


class RequestModifierPlugin : HttpClientPlugin<Unit, Unit> {
    override val key = AttributeKey<Unit>("RequestModifierPlugin")

    override fun prepare(block: Unit.() -> Unit) = Unit

    @OptIn(InternalAPI::class, ExperimentalSerializationApi::class)
    override fun install(plugin: Unit, scope: HttpClient) {
        val serverToolsList = serverTools().second
        val serverToolsJson = buildJsonArray {
            serverToolsList.forEach { tool ->
                add(buildJsonObject {
                    tool.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                })
            }
        }
        val serverToolTypes = serverToolsList.map { it["type"] }.toSet()

        scope.requestPipeline.intercept(HttpRequestPipeline.Transform) {
            val body = context.body as? String ?: return@intercept
            if (!body.contains("\"messages\"")) return@intercept

            try {
                val root = Json.parseToJsonElement(body).jsonObject
                val existingTools = root["tools"] as? JsonArray ?: buildJsonArray { }
                val existingToolTypes = existingTools.mapNotNull { tool ->
                    tool.jsonObject["type"]?.jsonPrimitive?.content
                }.toSet()
                val missingServerTools = buildJsonArray {
                    serverToolsJson.forEach { serverTool ->
                        val toolType = serverTool.jsonObject["type"]?.jsonPrimitive?.content
                        if (toolType !in existingToolTypes) {
                            add(serverTool)
                        }
                    }
                }
                if (missingServerTools.isEmpty()) {
                    proceed()
                    return@intercept
                }
                val mergedTools = buildJsonArray {
                    addAll(existingTools)
                    addAll(missingServerTools)
                }
                val updatedBody = Json.encodeToString(
                    JsonElement.serializer(),
                    JsonObject(root + ("tools" to mergedTools))
                )
                println("✅ RequestModifierPlugin: added ${missingServerTools.size} server tools (${missingServerTools.map { it.jsonObject["type"]?.jsonPrimitive?.content }})")
                proceedWith(TextContent(updatedBody, ContentType.Application.Json))
                return@intercept
            } catch (e: Exception) {
                println("⚠️ RequestModifierPlugin error: ${e.message}")
            }
            proceed()
        }
    }
}

val requestModifierPlugin = RequestModifierPlugin()

fun createModifiedOpenRouterExecutor(apiKey: String): ai.koog.prompt.executor.llms.SingleLLMPromptExecutor {
    val customHttpClient = HttpClient(CIO) {
        install(Logging) { level = LogLevel.ALL; logger = Logger.DEFAULT }
        install(requestModifierPlugin)
    }
    val openRouterClient = ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient(
        apiKey = apiKey,
        baseClient = customHttpClient
    )
    return ai.koog.prompt.executor.llms.SingleLLMPromptExecutor(openRouterClient)
}

val cModels = CModels()
infix fun String.from(func: KFunction<*>): String {
    val validParams = func.parameters
        .asSequence()
        .filter { it.kind == KParameter.Kind.VALUE && it.name != null }
        .map { it.name!! }
        .toSet()

    if (this !in validParams) {
        error("🔥 Prompt validation failed: parameter '$this' not found in ${func.name}(). Valid: $validParams")
    }
    return this
}

private val toolPromptRegistry = mutableMapOf<KFunction<*>, String>()

fun KFunction<*>.systemPrompt(block: KFunction<*>.() -> String) {
    toolPromptRegistry[this] = block()
}

fun KFunction<*>.systemPrompt(): String? = toolPromptRegistry[this]

MainAgentTools::modifyFile.systemPrompt {
    """
            Creates/modifies a file

            A specialized agent will read the file (if exists) or
            create it (if it doesn't exist) and apply the
            modifications
           
            IMPORTANT:
            After using this tool you should reread
            the file and check that modified file to check
            that modifications are applied correctly
            
            Apply further modifications if needed

            ### PARAMETERS

            - ${"filePath" from this}

              Absolute path to the file to create/modify 

            - ${"modificationInstructions" from this}

              Each modification instruction should strictly contain:

              1. Line numbers
              2. Operation:
                 * append -- appends (adds new lines AFTER the specified)
                 * delete -- delete the range of lines
                 * replace -- replaces one range of lines with another range of lines
                 * replaceInLine -- replaces some text within line with another text
              3. For append and replace operation -- exact new text
              4. For delete and replace operation -- description of the contents that should be replaced or deleted
              5. For replaceInLine operation -- searchText (text to find within the line) and replacementText (text to replace it with). This operation works only within a single line.

              IMPORTANT:

              1. Instructions shouldn't contain reasoning, only direct things to do
              2. Line numbers should refer to the ORIGINAL file state.
              3. Modifications must NOT overlap (disjoint ranges/points)
              6. Try to use this tool after you understand all needed changes to avoid runnning 
                 this tool twice against the same file
        """
}
//tag::toolprompt[]
var readFilemaxSizeKb = 40
MainAgentTools::readFile.systemPrompt {
    """
            Gets file contents by path with numbered lines (if required).
            If the file exceeds $readFilemaxSizeKb, returns only the first
            $readFilemaxSizeKb with a message indicating the limit.

            ### Params

            - ${"path" from this} -- absolute path to the file
            - ${"numberLines" from this} -- add line numbers to output
        """
}
//end::toolprompt[]
MainAgentTools::getDirectoryTreeJson.systemPrompt {
    """
            Gets file structure in JSON format by path, may return the whole tree or current directory.

            ### Params

            - ${"path" from MainAgentTools::getDirectoryTreeJson} -- absolute path
            - ${"wholeTree" from MainAgentTools::getDirectoryTreeJson} -- whole tree (true) or current directory (false)
            - ${"threshold" from MainAgentTools::getDirectoryTreeJson} -- maximum number of nodes allowed before truncation, default -- 100
        """
}
MainAgentTools::analyzeFile.systemPrompt {
    """
        Analyzes a file (image or PDF) and answers a specific question about its contents.
        Supports only: .jpg, .jpeg, .png, .svg (images) and .pdf (documents).
        Returns an error for unsupported types or files larger than 15MB.

        ### Parameters
        - ${"filePath" from this} -- absolute path to the file
        - ${"question" from this} -- specific question to answer based on file contents

        ### Returns
        Detailed analysis result or error message.
    """
}


val fixingParser = StructureFixingParser(model = cModels.qwen36Plus, retries = 3)

fun outputAnswer(response: String, file: File) {
    val hasFirstLevelHeading = Regex("^# ").containsMatchIn(response)

    val processedResponse = if (hasFirstLevelHeading) {
        response.replace(Regex("^#+ ")) { match ->
            val hashCount = match.value.count { it == '#' }
            "${"#".repeat(hashCount + 1)} "
        }
    } else {
        response
    }

    val content = file.readText().trimEnd()
    val updatedContent = content + "\n\n# As\n\n$processedResponse\n\n# Us"

    file.writeText(updatedContent)
}

fun unescapeToolContent(content: String): String {
    return try {
        val element = Json.parseToJsonElement(content)
        if (element is JsonPrimitive && element.isString) {
            element.jsonPrimitive.content
        } else {
            content
        }
    } catch (e: Exception) {
        content
    }
}

fun saveCurrentPrompt(promptFilePath: String, iteration: Int, messages: List<Message>) {

    // Фильтруем системные сообщения и форматируем согласно правилам
    val nonSystemMessages = messages //.filter { it.role != Message.Role.System }

    if (nonSystemMessages.isEmpty()) return

    val promptDir = File(promptFilePath).parent
    val promptFileName = File(promptFilePath).nameWithoutExtension
    val currentPromptFile = File(promptDir, "$promptFileName.current.md")

    val formattedMessages = buildString {
        nonSystemMessages.forEachIndexed { index, message ->
            when (message.role) {
                Message.Role.Tool -> {
                    if (message is Message.Tool.Call) {
                        append("# As\n\nToolCall: ${message.tool}\n\n")
                        append("${message.content}\n\n")
                    } else if (message is Message.Tool.Result) {
                        append("# Us\n\nToolResult: ${message.tool}\n\n")
                        append("${unescapeToolContent(message.content)}\n\n")
                    }
                }

                Message.Role.User -> append("# Us\n\n${message.content}\n\n")
                Message.Role.Assistant -> append("# As\n\n${message.content}\n\n")
                else -> {}
            }
        }
    }

    currentPromptFile.writeText(formattedMessages)
    info("💾 Saved current prompt to ${currentPromptFile.absolutePath} (iteration $iteration)")
}

fun parsePromptWithMarkers(prompt: String): List<Pair<Message.Role, String>> {
    val messages = mutableListOf<Pair<Message.Role, String>>()
    val lines = prompt.lines()

    var currentRole: Message.Role = Message.Role.User
    val currentContent = StringBuilder()
    var isMarkerMode = false

    for (line in lines) {
        if (line.startsWith("# As") || line.startsWith("# Us")) {
            if (currentContent.isNotEmpty()) {
                messages.add(Pair(currentRole, currentContent.toString()))
                currentContent.clear()
            }

            currentRole = if (line.startsWith("# As")) {
                Message.Role.Assistant
            } else {
                Message.Role.User
            }
            isMarkerMode = true
        } else {
            if (currentContent.isNotEmpty() && isMarkerMode) {
                currentContent.append("\n")
            }
            currentContent.append(line)
        }
    }

    if (currentContent.isNotEmpty()) {
        messages.add(Pair(currentRole, currentContent.toString()))
    }

    return messages
}

fun info(message: String) {
    val datetime =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())
    val lines = message.lines()
    if (lines.size <= 1) {
        println("$datetime $message")
    } else {
        println(datetime)
        lines.forEach { println(it) }
    }
}

class FileAnalysisSubAgent {

    fun analyzeFile(filePath: String, question: String): String {
        val file = File(filePath)
        if (!file.exists()) return "ERROR: Файл не найден: $filePath"
        if (file.length() > 15_000_000) return "ERROR: Файл слишком большой (>15MB)"

        val ext = file.extension.lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "svg" -> analyzeImage(file, question)
            "pdf" -> analyzePDF(file, question)
            else -> "ERROR: Неподдерживаемый тип файла. Поддерживаются только: jpg, jpeg, png, svg, pdf"
        }
    }

    private fun analyzeImage(file: File, question: String): String {
        info("Analyzing image: ${file.name}")
        val prompt = Prompt.build("image-analysis") {
            system("Ты эксперт по анализу изображений. Внимательно изучи изображение и ответь на вопрос пользователя максимально подробно.")
            user {
                text(question)
                image(KPath(file.absolutePath))
            }
        }
        return runBlocking {
            val executor = createModifiedOpenRouterExecutor(cModels.apiKey)
            val response = executor.execute(prompt, cModels.qwen36Plus)
            response.joinToString("\n\n") { it.content }
        }
    }

    private fun analyzePDF(file: File, question: String): String {
        info("Analyzing PDF: ${file.name}")
        val prompt = Prompt.build("pdf-analysis") {
            system("Ты эксперт по анализу документов. Изучи содержимое PDF и ответь на вопрос пользователя.")
            user {
                text(question)
                binaryFile(KPath(file.absolutePath), "application/pdf")
            }
        }
        return runBlocking {
            val executor = createModifiedOpenRouterExecutor(cModels.apiKey)
            val response = executor.execute(prompt, cModels.qwen36Plus)
            response.joinToString("\n\n") { it.content }
        }
    }
}

class MainAgentTools : ToolSet {

    companion object {
        private val readFiles = mutableSetOf<String>()
    }

    private val proposalSubAgent = ProposalSubAgent()
    private val fileAnalysisSubAgent = FileAnalysisSubAgent()

    @Tool
    fun modifyFile(filePath: String, modificationInstructions: String): String {
        info("${MainAgentTools::modifyFile.name}: $filePath\n\n${modificationInstructions}\n")
        val canonicalPath = File(filePath).absolutePath
        val proposalFile = File("$filePath.proposal")
        if (proposalFile.exists() && canonicalPath !in readFiles) {
            return "ERROR: File '$filePath' has been modified. " +
                   "You must read the current state first " +
                    "before making further modifications."
        }
        readFiles.remove(canonicalPath)
        return proposalSubAgent.createProposal(filePath, modificationInstructions)
    }

    @Tool
    fun readFile(path: String, numberLines: Boolean): String {
        readFiles.add(File(path).absolutePath)
        val proposalFile = File("$path.proposal")
        val file = if (proposalFile.exists()) {
            info("readFile: proposal found, reading ${proposalFile.name} instead")
            proposalFile
        } else {
            File(path)
        }
        info("readFile is run: ${file.absolutePath} (numberLines: $numberLines)")

        if (!file.exists()) return "ERROR: File doesn't exist"
        val maxSize = readFilemaxSizeKb * 1024

        val rawLines = if (file.length() > maxSize) {
            val content = file.readBytes().sliceArray(0 until maxSize)
            String(content).lineSequence().toList()
        } else {
            file.readLines()
        }
        if (rawLines.size == 0)  return "The file is empty"

        val outputContent = if (numberLines) {
            rawLines.mapIndexed { index, line ->
                "${index + 1}: $line"
            }.joinToString("\n")
        } else rawLines.joinToString("\n")

        val redactedContent = redactText(outputContent)

        val lineNumberIstruction = if (numberLines) "The returned file content includes line numbers. Each line is prefixed with the line number followed by a colon. For example, 1: println(\"first line\") means line number one contains the code println(\"first line\").\n\n" else ""


        return if (file.length() > maxSize) {
            "Returned only first ${readFilemaxSizeKb}Kb of a file]\n\n$lineNumberIstruction$redactedContent\n\n"
        } else {
            "${lineNumberIstruction}${redactedContent}"
        }
    }


    @Serializable
    data class Node(
        val name: String,
        val children: List<Node>? = null
    )

    @Tool
    fun getDirectoryTreeJson(path: String, wholeTree: Boolean = false, threshold: Int = 100): String {
        info("getDirectoryTreeJson is run: $path, $wholeTree, threshold=$threshold")
        val file = File(path)
        if (!file.exists()) {
            return """{"error": "Path does not exist: $path"}"""
        }

        val rootNode = Node(file.name, mutableListOf<Node>())
        var totalNodes = 1
        var message: String? = null

        var currentLevelFiles = listOf(file)
        var currentLevelNodes = listOf(rootNode)
        val maxExpansions = if (wholeTree) Int.MAX_VALUE else 1
        var completedExpansions = 0

        while (currentLevelFiles.isNotEmpty() && completedExpansions < maxExpansions && message == null) {
            val nextLevelFiles = mutableListOf<File>()
            val nextLevelNodes = mutableListOf<Node>()

            for ((dirFile, parentNode) in currentLevelFiles.zip(currentLevelNodes)) {
                val parentChildren = parentNode.children as MutableList<Node>
                val childrenFiles = dirFile.listFiles()?.toList() ?: emptyList()

                for (childFile in childrenFiles) {
                    if (shouldIgnore(childFile.name)) continue
                    if (childFile.isDirectory && Files.isSymbolicLink(childFile.toPath())) continue

                    val childNode = Node(childFile.name, mutableListOf<Node>())
                    parentChildren.add(childNode)
                    totalNodes++

                    if (totalNodes > threshold) {
                        parentChildren.remove(childNode) // Rollback to maintain complete levels only
                        totalNodes--
                        message =
                            "the number of nodes in a tree exceeds $threshold, so returned only first ${completedExpansions + 1} levels"
                        break
                    }

                    if (childFile.isDirectory) {
                        nextLevelFiles.add(childFile)
                        nextLevelNodes.add(childNode)
                    }
                }
                if (message != null) break
            }

            if (message == null) {
                currentLevelFiles = nextLevelFiles
                currentLevelNodes = nextLevelNodes
                completedExpansions++
            }
        }

        fun freeze(node: Node): Node = node.copy(children = node.children?.map { freeze(it) })
        val frozenRoot = freeze(rootNode)

        val treeJson = Json { prettyPrint = false }.encodeToString(frozenRoot)
        return if (message != null) "$message\n\n$treeJson" else treeJson
    }

    private val ignoreRe = arrayOf<String>(".*\\.proposal") // arrayOf("""\..*""", """build""")

    private fun shouldIgnore(name: String): Boolean {
        return ignoreRe.any { pattern -> Regex(pattern).matches(name) }
    }

    @Tool
    fun analyzeFile(filePath: String, question: String): String {
        info("Tool: analyzeFile -> $filePath")
        return fileAnalysisSubAgent.analyzeFile(filePath, question)
    }


}

fun List<String>.withLineNumbers(): String =
    this.mapIndexed { index, line ->
        "${index + 1}: $line"
    }.joinToString("\n")

class ProposalSubAgent {
    @Serializable
    @LLMDescription("A wrapper for a list of modifications")
    data class ModificationList(
        @property:LLMDescription("List of modifications sorted by line number in descending order")
        val modifications: List<Modification>
    )

    @Serializable
    enum class ModificationType { APPEND, DELETE, REPLACE, REPLACE_IN_LINE }

    @Serializable
    @LLMDescription("A single modification instruction for a file")
    data class Modification(
        @property:LLMDescription("Type of modification")
        val type: ModificationType,

        @property:LLMDescription(
            "Start line number (1-based). \n" +
                    "- For APPEND: The line AFTER which to insert.\n" +
                    "- For REPLACE/DELETE: The first line to modify/delete.\n" +
                    "- For REPLACE_IN_LINE: The line number where text replacement should occur."
        )
        val startLine: Int,

        @property:LLMDescription(
            "End line number (1-based, inclusive). \n" +
                    "- Required for REPLACE and DELETE.\n" +
                    "- Null for APPEND and REPLACE_IN_LINE."
        )
        val endLine: Int? = null,

        @property:LLMDescription(
            "Content string. \n" +
                    "- Required for APPEND (content to insert) and REPLACE (new content).\n" +
                    "- Null for DELETE and REPLACE_IN_LINE."
        )
        val contents: String? = null,

        @property:LLMDescription(
            "Text to search for within the line. Only used for REPLACE_IN_LINE operation."
        )
        val searchText: String? = null,

        @property:LLMDescription(
            "Text to replace searchText with. Only used for REPLACE_IN_LINE operation."
        )
        val replacementText: String? = null
    )

    data class ModificationRequest(
        val fileContent: String,
        val globalInstructions: String
    )

    @OptIn(InternalAgentToolsApi::class)
    fun generateModificationSchemaToJson(): String {
        val schema = getJsonSchema(
            typeToken = typeToken<ModificationList>(),
            jsonSchemaConfig = defaultJsonSchemaConfig
        )
        return Json { prettyPrint = true }
            .encodeToString(kotlinx.schema.json.JsonSchema.serializer(), schema)
    }

    fun createModificationPlannerAgent(): AIAgent<ModificationRequest, List<Modification>> {
        val agentStrategy =
            functionalStrategy<ModificationRequest, List<Modification>>("modification-planner") { request ->
                val schemaJson = generateModificationSchemaToJson()

                val promptMessage = arrayOf(
                    "# Modification instruction:",
                    request.globalInstructions,
                    "",
                    "# Results must be presented in JSON format of the following schema:",
                    schemaJson
                ).joinToString("\n\n")

                val result = requestLLMStructured<ModificationList>(promptMessage, fixingParser = fixingParser)

                result.getOrThrow().data.modifications.sortedByDescending { it.startLine }
            }

        val agentConfig = AIAgentConfig(
            prompt = Prompt.build(
                id = "modification-planner-prompt",
                params = LLMParams(
                    temperature = 0.7,
                    additionalProperties = additionalPropertiesOf(
                        "reasoning" to mapOf("enabled" to false)
                    )
                )
            ) {
                system(
                    """
                    You are a code modification planner.
                    Generate a modification list based on the given instructions.

                    IMPORTANT
                    * line numbers are 1-based and they should be 1-based in a modification list""".trimIndent()
                )
            },
            model = cModels.qwen36Plus,
            maxAgentIterations = 5
        )

        return AIAgent(
            createModifiedOpenRouterExecutor(cModels.apiKey),
            strategy = agentStrategy,
            agentConfig = agentConfig
        )
    }

    fun createProposal(
        originalFilePath: String,
        modificationInstructions: String
    ): String {
        val originalFile = File(originalFilePath)

        val proposalFile = File(
            originalFile.parentFile,
            "${originalFile.name}.proposal"
        )

        val modificationRequest = if (proposalFile.exists()) {
            // Proposal already exists — further modify it, don't overwrite from original
            ModificationRequest(proposalFile.readText(), modificationInstructions)
        } else if (originalFile.exists()) {
            // First call — copy original to proposal, then modify
            originalFile.copyTo(proposalFile, overwrite = true)
            ModificationRequest(originalFile.readText(), modificationInstructions)
        } else {
            proposalFile.writeText("")
            ModificationRequest("", modificationInstructions)
        }

        val modifications =
            runBlocking { createModificationPlannerAgent().run(modificationRequest) }

        applyModifications(File(proposalFile.absolutePath), modifications)

        return "File ${originalFile.absolutePath} was successfully modified"
    }

    fun applyModifications(file: File, modifications: List<Modification>) {
        if (modifications.isEmpty()) return

        val lines = file.readLines().toMutableList()

        for (mod in modifications) {
            when (mod.type) {
                ModificationType.APPEND -> {
                    val insertIndex = mod.startLine

                    val safeIndex = insertIndex.coerceIn(0, lines.size)

                    val linesToInsert = mod.contents?.lines() ?: emptyList()
                    if (linesToInsert.isNotEmpty()) {
                        lines.addAll(safeIndex, linesToInsert)
                    }
                }

                ModificationType.DELETE -> {
                    val startIndex = mod.startLine - 1
                    val endIndex = (mod.endLine ?: mod.startLine) - 1

                    if (startIndex >= 0 && endIndex < lines.size && startIndex <= endIndex) {
                        lines.subList(startIndex, endIndex + 1).clear()
                    }
                }

                ModificationType.REPLACE -> {
                    val startIndex = mod.startLine - 1
                    val endIndex = (mod.endLine ?: mod.startLine) - 1

                    val newLines = mod.contents?.lines() ?: emptyList()

                    if (startIndex >= 0 && endIndex < lines.size && startIndex <= endIndex) {
                        lines.subList(startIndex, endIndex + 1).clear()
                        lines.addAll(startIndex, newLines)
                    }
                }

                ModificationType.REPLACE_IN_LINE -> {
                    val lineIndex = mod.startLine - 1

                    if (lineIndex >= 0 && lineIndex < lines.size && mod.searchText != null && mod.replacementText != null) {
                        val originalLine = lines[lineIndex]
                        lines[lineIndex] = originalLine.replace(mod.searchText, mod.replacementText)
                    }
                }
            }
        }

        file.writeText(lines.joinToString("\n"))
    }
}

class CModels {
    val apiKeyFile = File("apikey")
    val apiKey = if (!apiKeyFile.exists()) {
        val absolutePath = apiKeyFile.absolutePath
        error("API key file not found at '$absolutePath'. Please create a file containing only your OpenRouter API key (sk-or-v1-...)")
    } else {
        apiKeyFile.readText().trim()
    }

    private val standardCapabilities: List<LLMCapability> = listOf(
        LLMCapability.Schema.JSON.Standard,
        LLMCapability.Completion,
        LLMCapability.Tools,
        LLMCapability.ToolChoice,
        LLMCapability.Vision.Image
    )

    fun llModel(modelId: String, contextLength: Long, maxOutputTokens: Long) = LLModel(
        LLMProvider.OpenRouter,
        modelId,
        standardCapabilities,
        contextLength = contextLength,
        maxOutputTokens = maxOutputTokens,
    )

    val qwen36Plus = llModel("qwen/qwen3.6-plus", 1_000_000, 65_500)
    val qwen35Flash = llModel("qwen/qwen3.5-flash-02-23", 1_000_000, 65_500)
    val qwen3X235bA22b = llModel("qwen/qwen3-235b-a22b", 131_072, 8_200)
    val qwen3X235bA22bX2507 = llModel("qwen/qwen3-235b-a22b-2507", 131_072, 8_200)
    val qwen35X397bA17b = llModel("qwen/qwen3.5-397b-a17b", 131_072, 8_200)
    val gpt5 = llModel("openai/gpt-5.4", 1_000_000, 128_000)
}

fun mainAgent(aiAgentConfig: AIAgentConfig) = AIAgent(
    createModifiedOpenRouterExecutor(cModels.apiKey),
    agentConfig = aiAgentConfig,
    strategy = functionalStrategy<String, String> { input ->
        val toolPrompts = currentTools().mapNotNull { toolRef ->
            toolRef.systemPrompt()
        }.ifEmpty { null }?.joinToString("\n\n------\n\n")

        llm().writeSession {
            for ((role, content) in parsePromptWithMarkers(input)) {
                appendPrompt {
                    toolPrompts?.let { system(it) }
                    when (role) {
                        Message.Role.User -> user(content)
                        Message.Role.Assistant -> assistant(content)
                        else -> {}
                    }
                }
            }
        }

        var responses = llm().writeSession { requestLLMMultiple() }

        for (iteration in 1..30) {
            if (!responses.containsToolCalls()) break
            info("iteration: $iteration")

            val pendingCalls = extractToolCalls(responses)
            val results = executeMultipleTools(pendingCalls)
            responses = sendMultipleToolResults(results)
            saveCurrentPrompt(
                promptFilePath(),
                iteration,
                llm().readSession { prompt.messages }
            )

            val responseMessages = llm().readSession { prompt.messages.filterIsInstance<Message.Response>() }
            val totalInputTokens = responseMessages.sumOf { it.metaInfo.inputTokensCount ?: 0 }
            val totalOutputTokens = responseMessages.sumOf { it.metaInfo.outputTokensCount ?: 0 }

            info("Actual tokens after iteration $iteration - Input: $totalInputTokens, Output: $totalOutputTokens")
        }
        val allResponses = llm().readSession { prompt.messages.filterIsInstance<Message.Response>() }
        val finalInputTokens = allResponses.sumOf { it.metaInfo.inputTokensCount ?: 0 }
        val finalOutputTokens = allResponses.sumOf { it.metaInfo.outputTokensCount ?: 0 }
        info("Final actual tokens - Input: $finalInputTokens, Output: $finalOutputTokens")

        val finalContent = if (responses.containsToolCalls()) {
            requestLLM(
                "Tool calling limit reached. Now based on gathered data work out the final answer",
                allowToolCalls = false
            ).content
        } else {
            responses.joinToString("\n\n") { it.content }
        }

        finalContent
    },
    toolRegistry = ToolRegistry {
        tools(
            MainAgentTools().asTools()
                .filter { tool -> currentTools().any { currentTool -> currentTool.name == tool.name } })
    },

    )


val aiAgentConfig = AIAgentConfig(
    prompt = Prompt.build(
        id = "main-prompt",
        params = LLMParams(
            temperature = 0.7,
            additionalProperties = additionalProperties()
        )
    ) { system("""You are a helpful assistant.""") },
    model = cmodel(),
    maxAgentIterations = 50,
)

val promptFileObj = File(promptFilePath())
if (!promptFileObj.exists()) {
    info("Prompt file not found: ${promptFileObj.absolutePath}")
    if (args.isEmpty()) {
        info("No command line argument provided, and 'prompt.md' doesn't exist in the current directory.")
    }
    kotlin.system.exitProcess(1)
}

info("start v1")
val response =
    runBlocking { mainAgent(aiAgentConfig).run(promptFileObj.readText()) }
outputAnswer(response, promptFileObj)
info("finish")

fun promptFilePath() = if (args.isNotEmpty()) args[0] else "prompt.md"

//fun cmodel() = cModels.qwen35Flash
//fun cmodel() = cModels.gpt5
//tag::params[]
fun cmodel() = cModels.qwen36Plus
fun serverTools() = "tools" to listOf<Map<String, String>>(
//    mapOf("type" to "openrouter:web_search"),
//    mapOf("type" to "openrouter:web_fetch")
)

fun additionalProperties() = additionalPropertiesOf(
    "reasoning" to mapOf("enabled" to true),
    serverTools()
)

fun currentTools() = setOf<KFunction<String>>(
    MainAgentTools::readFile,
    MainAgentTools::getDirectoryTreeJson,
    MainAgentTools::modifyFile,
//    MainAgentTools::analyzeFile,
)
//end::params[]