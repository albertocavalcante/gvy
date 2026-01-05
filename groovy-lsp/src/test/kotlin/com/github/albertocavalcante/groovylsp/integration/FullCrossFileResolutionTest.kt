package com.github.albertocavalcante.groovylsp.integration

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.providers.completion.CompletionProvider
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionProvider
import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase -2: Comprehensive FAILING integration tests for full cross-file semantic resolution.
 *
 * These tests define the expected behavior for workspace-wide semantic resolution,
 * transforming from per-file compilation to a semantic model like Metals/IntelliJ/Eclipse JDT LS.
 *
 * All tests are marked @Disabled and expected to FAIL initially (TDD approach).
 * They will be enabled and made to pass in subsequent phases.
 *
 * Test Coverage:
 * - Cross-file symbol resolution (fields, methods, constructors, properties, static members)
 * - Inheritance chain resolution (parent classes, interfaces, transitive inheritance)
 * - Type inference across files
 * - Import resolution (star imports, static imports, aliased imports)
 * - Completion across files
 */
class FullCrossFileResolutionTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var definitionProvider: DefinitionProvider
    private lateinit var semanticResolver: SemanticTypeResolver

    @BeforeEach
    fun setUp() {
        compilationService = GroovyCompilationService()
        definitionProvider = DefinitionProvider(compilationService)
        semanticResolver = SemanticTypeResolver(ReflectionTypeSolver())
    }

    @AfterEach
    fun tearDown() {
        compilationService.clearCaches()
    }

    // ==========================================================================
    // Category 1: Cross-file Symbol Resolution
    // ==========================================================================

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `cross-file field access resolution`() = runTest {
        // Setup: ServiceB has a field, ServiceA accesses it
        val fileB = URI.create("file:///ServiceB.groovy")
        val fileBContent = """
            class ServiceB {
                static String sharedField = "shared"
            }
        """.trimIndent()

        val fileA = URI.create("file:///ServiceA.groovy")
        val fileAContent = """
            class ServiceA {
                def useService() {
                    return ServiceB.sharedField
                }
            }
        """.trimIndent()

        // Compile both files
        compilationService.compile(fileB, fileBContent)
        compilationService.compile(fileA, fileAContent)

        // Act: Request definition at 'sharedField' reference in ServiceA
        // Line 2, column 36 points to 'sharedField' in 'ServiceB.sharedField'
        val definitions = definitionProvider.provideDefinitions(fileA.toString(), Position(2, 36)).toList()

        // Assert: Should navigate to field definition in ServiceB
        assertFalse(definitions.isEmpty(), "Should find cross-file field definition")
        assertEquals(1, definitions.size)
        assertEquals(fileB.toString(), definitions[0].uri)
        assertEquals(1, definitions[0].range.start.line) // Line where sharedField is declared
        assertTrue(definitions[0].range.start.character >= 18) // Column of 'sharedField'
    }

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `cross-file method call resolution`() = runTest {
        // Setup: HelperClass has a method, UtilClass calls it
        val helperFile = URI.create("file:///HelperClass.groovy")
        val helperContent = """
            class HelperClass {
                static String helperMethod(String input) {
                    return input.toUpperCase()
                }
            }
        """.trimIndent()

        val utilFile = URI.create("file:///UtilClass.groovy")
        val utilContent = """
            class UtilClass {
                def process(String data) {
                    return HelperClass.helperMethod(data)
                }
            }
        """.trimIndent()

        // Compile both files
        compilationService.compile(helperFile, helperContent)
        compilationService.compile(utilFile, utilContent)

        // Act: Request definition at 'helperMethod' call in UtilClass
        // Line 2, column 38 points to 'helperMethod' in the method call
        val definitions = definitionProvider.provideDefinitions(utilFile.toString(), Position(2, 38)).toList()

        // Assert: Should navigate to method definition in HelperClass
        assertFalse(definitions.isEmpty(), "Should find cross-file method definition")
        assertEquals(helperFile.toString(), definitions[0].uri)
        assertEquals(1, definitions[0].range.start.line) // Line where helperMethod is declared
    }

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `cross-file constructor call resolution`() = runTest {
        // Setup: PersonModel class, PersonFactory creates instances
        val modelFile = URI.create("file:///PersonModel.groovy")
        val modelContent = """
            class PersonModel {
                String name
                int age

                PersonModel(String name, int age) {
                    this.name = name
                    this.age = age
                }
            }
        """.trimIndent()

        val factoryFile = URI.create("file:///PersonFactory.groovy")
        val factoryContent = """
            class PersonFactory {
                def createPerson(String name, int age) {
                    return new PersonModel(name, age)
                }
            }
        """.trimIndent()

        // Compile both files
        compilationService.compile(modelFile, modelContent)
        compilationService.compile(factoryFile, factoryContent)

        // Act: Request definition at 'PersonModel' constructor call
        // Line 2, column 28 points to 'PersonModel' in the constructor call
        val definitions = definitionProvider.provideDefinitions(factoryFile.toString(), Position(2, 28)).toList()

        // Assert: Should navigate to constructor definition in PersonModel
        assertFalse(definitions.isEmpty(), "Should find cross-file constructor definition")
        assertEquals(modelFile.toString(), definitions[0].uri)
        // Should point to constructor or class definition
        assertTrue(definitions[0].range.start.line <= 4) // Constructor is at line 4
    }

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `cross-file property access resolution`() = runTest {
        // Setup: ConfigHolder with a property, ConfigUser accesses it
        val holderFile = URI.create("file:///ConfigHolder.groovy")
        val holderContent = """
            class ConfigHolder {
                String configValue

                String getConfigValue() { return configValue }
                void setConfigValue(String val) { configValue = val }
            }
        """.trimIndent()

        val userFile = URI.create("file:///ConfigUser.groovy")
        val userContent = """
            class ConfigUser {
                def readConfig(ConfigHolder holder) {
                    return holder.configValue
                }
            }
        """.trimIndent()

        // Compile both files
        compilationService.compile(holderFile, holderContent)
        compilationService.compile(userFile, userContent)

        // Act: Request definition at 'configValue' property access
        // Line 2, column 33 points to 'configValue' in property access
        val definitions = definitionProvider.provideDefinitions(userFile.toString(), Position(2, 33)).toList()

        // Assert: Should navigate to property/field definition or getter
        assertFalse(definitions.isEmpty(), "Should find cross-file property definition")
        assertEquals(holderFile.toString(), definitions[0].uri)
    }

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `cross-file static member access resolution`() = runTest {
        // Setup: Constants class with static members, Application uses them
        val constantsFile = URI.create("file:///Constants.groovy")
        val constantsContent = """
            class Constants {
                static final String APP_NAME = "MyApp"
                static final int MAX_CONNECTIONS = 100

                static String getVersion() { return "1.0.0" }
            }
        """.trimIndent()

        val appFile = URI.create("file:///Application.groovy")
        val appContent = """
            class Application {
                def initialize() {
                    println Constants.APP_NAME
                    def max = Constants.MAX_CONNECTIONS
                    def ver = Constants.getVersion()
                }
            }
        """.trimIndent()

        // Compile both files
        compilationService.compile(constantsFile, constantsContent)
        compilationService.compile(appFile, appContent)

        // Act: Request definition at 'APP_NAME' static field access
        // Line 2, column 34 points to 'APP_NAME'
        val appNameDefs = definitionProvider.provideDefinitions(appFile.toString(), Position(2, 34)).toList()

        // Assert: Should navigate to static field definition
        assertFalse(appNameDefs.isEmpty(), "Should find static field definition")
        assertEquals(constantsFile.toString(), appNameDefs[0].uri)
        assertEquals(1, appNameDefs[0].range.start.line)

        // Act: Request definition at 'getVersion' static method call
        // Line 4, column 40 points to 'getVersion'
        val versionDefs = definitionProvider.provideDefinitions(appFile.toString(), Position(4, 40)).toList()

        // Assert: Should navigate to static method definition
        assertFalse(versionDefs.isEmpty(), "Should find static method definition")
        assertEquals(constantsFile.toString(), versionDefs[0].uri)
    }

    // ==========================================================================
    // Category 2: Inheritance Chain Resolution
    // ==========================================================================

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `inherited field from parent class in different file`() = runTest {
        // Setup: BaseEntity in one file, UserEntity extends it in another
        val baseFile = URI.create("file:///BaseEntity.groovy")
        val baseContent = """
            class BaseEntity {
                String id
                Date createdAt
            }
        """.trimIndent()

        val userFile = URI.create("file:///UserEntity.groovy")
        val userContent = """
            class UserEntity extends BaseEntity {
                String username

                def printId() {
                    println this.id
                }
            }
        """.trimIndent()

        // Compile both files
        compilationService.compile(baseFile, baseContent)
        compilationService.compile(userFile, userContent)

        // Act: Request definition at inherited 'id' field access
        // Line 4, column 26 points to 'id' in this.id
        val definitions = definitionProvider.provideDefinitions(userFile.toString(), Position(4, 26)).toList()

        // Assert: Should navigate to field definition in parent class
        assertFalse(definitions.isEmpty(), "Should find inherited field definition")
        assertEquals(baseFile.toString(), definitions[0].uri)
        assertEquals(1, definitions[0].range.start.line) // Line where id is declared
    }

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `inherited method from parent class in different file`() = runTest {
        // Setup: BaseService with method, UserService extends it
        val baseFile = URI.create("file:///BaseService.groovy")
        val baseContent = """
            class BaseService {
                protected void log(String message) {
                    println message
                }
            }
        """.trimIndent()

        val userFile = URI.create("file:///UserService.groovy")
        val userContent = """
            class UserService extends BaseService {
                def performAction() {
                    log("Action performed")
                }
            }
        """.trimIndent()

        // Compile both files
        compilationService.compile(baseFile, baseContent)
        compilationService.compile(userFile, userContent)

        // Act: Request definition at inherited 'log' method call
        // Line 2, column 20 points to 'log' method call
        val definitions = definitionProvider.provideDefinitions(userFile.toString(), Position(2, 20)).toList()

        // Assert: Should navigate to method definition in parent class
        assertFalse(definitions.isEmpty(), "Should find inherited method definition")
        assertEquals(baseFile.toString(), definitions[0].uri)
        assertEquals(1, definitions[0].range.start.line)
    }

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `interface method implementation resolution`() = runTest {
        // Setup: Runnable interface, Task implements it
        val interfaceFile = URI.create("file:///Runnable.groovy")
        val interfaceContent = """
            interface Runnable {
                void run()
            }
        """.trimIndent()

        val implFile = URI.create("file:///Task.groovy")
        val implContent = """
            class Task implements Runnable {
                @Override
                void run() {
                    println "Task running"
                }
            }
        """.trimIndent()

        // Compile both files
        compilationService.compile(interfaceFile, interfaceContent)
        compilationService.compile(implFile, implContent)

        // Act: Request definition at 'Runnable' interface reference
        // Line 0, column 24 points to 'Runnable'
        val definitions = definitionProvider.provideDefinitions(implFile.toString(), Position(0, 24)).toList()

        // Assert: Should navigate to interface definition
        assertFalse(definitions.isEmpty(), "Should find interface definition")
        assertEquals(interfaceFile.toString(), definitions[0].uri)
        assertEquals(0, definitions[0].range.start.line)
    }

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `transitive inheritance - grandparent class resolution`() = runTest {
        // Setup: Three-level hierarchy across files
        val grandparentFile = URI.create("file:///Entity.groovy")
        val grandparentContent = """
            class Entity {
                String uuid
            }
        """.trimIndent()

        val parentFile = URI.create("file:///DomainEntity.groovy")
        val parentContent = """
            class DomainEntity extends Entity {
                String domain
            }
        """.trimIndent()

        val childFile = URI.create("file:///UserEntity.groovy")
        val childContent = """
            class UserEntity extends DomainEntity {
                String username

                def printUuid() {
                    println this.uuid
                }
            }
        """.trimIndent()

        // Compile all three files
        compilationService.compile(grandparentFile, grandparentContent)
        compilationService.compile(parentFile, parentContent)
        compilationService.compile(childFile, childContent)

        // Act: Request definition at 'uuid' field from grandparent
        // Line 4, column 26 points to 'uuid' in this.uuid
        val definitions = definitionProvider.provideDefinitions(childFile.toString(), Position(4, 26)).toList()

        // Assert: Should navigate to field in grandparent class
        assertFalse(definitions.isEmpty(), "Should find field in grandparent class")
        assertEquals(grandparentFile.toString(), definitions[0].uri)
        assertEquals(1, definitions[0].range.start.line)
    }

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `diamond inheritance resolution`() = runTest {
        // Setup: Diamond pattern - A <- B, A <- C, D <- B & C
        val baseFile = URI.create("file:///BaseFeature.groovy")
        val baseContent = """
            interface BaseFeature {
                String getFeatureName()
            }
        """.trimIndent()

        val feature1File = URI.create("file:///Feature1.groovy")
        val feature1Content = """
            interface Feature1 extends BaseFeature {
                void feature1Method()
            }
        """.trimIndent()

        val feature2File = URI.create("file:///Feature2.groovy")
        val feature2Content = """
            interface Feature2 extends BaseFeature {
                void feature2Method()
            }
        """.trimIndent()

        val combinedFile = URI.create("file:///CombinedFeature.groovy")
        val combinedContent = """
            class CombinedFeature implements Feature1, Feature2 {
                @Override
                String getFeatureName() { return "Combined" }

                @Override
                void feature1Method() { println "F1" }

                @Override
                void feature2Method() { println "F2" }
            }
        """.trimIndent()

        // Compile all files
        compilationService.compile(baseFile, baseContent)
        compilationService.compile(feature1File, feature1Content)
        compilationService.compile(feature2File, feature2Content)
        compilationService.compile(combinedFile, combinedContent)

        // Act: Request definition at 'Feature1' interface reference
        // Line 0, column 42 points to 'Feature1'
        val feature1Defs = definitionProvider.provideDefinitions(combinedFile.toString(), Position(0, 42)).toList()

        // Assert: Should navigate to Feature1 interface
        assertFalse(feature1Defs.isEmpty(), "Should find Feature1 interface")
        assertEquals(feature1File.toString(), feature1Defs[0].uri)

        // Act: Request definition at 'Feature2' interface reference
        // Line 0, column 52 points to 'Feature2'
        val feature2Defs = definitionProvider.provideDefinitions(combinedFile.toString(), Position(0, 52)).toList()

        // Assert: Should navigate to Feature2 interface
        assertFalse(feature2Defs.isEmpty(), "Should find Feature2 interface")
        assertEquals(feature2File.toString(), feature2Defs[0].uri)
    }

    // ==========================================================================
    // Category 3: Type Inference Across Files
    // ==========================================================================

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `infer type from cross-file method return`() = runTest {
        // Setup: DataProvider returns DataModel from another file
        val modelFile = URI.create("file:///DataModel.groovy")
        val modelContent = """
            class DataModel {
                String value
                int count
            }
        """.trimIndent()

        val providerFile = URI.create("file:///DataProvider.groovy")
        val providerContent = """
            class DataProvider {
                DataModel getData() {
                    return new DataModel(value: "test", count: 42)
                }
            }
        """.trimIndent()

        val consumerFile = URI.create("file:///DataConsumer.groovy")
        val consumerContent = """
            class DataConsumer {
                def process(DataProvider provider) {
                    def data = provider.getData()
                    return data.value
                }
            }
        """.trimIndent()

        // Compile all files
        compilationService.compile(modelFile, modelContent)
        compilationService.compile(providerFile, providerContent)
        compilationService.compile(consumerFile, consumerContent)

        // Act: Request definition at 'value' field access on inferred type
        // Line 3, column 28 points to 'value' in data.value
        val definitions = definitionProvider.provideDefinitions(consumerFile.toString(), Position(3, 28)).toList()

        // Assert: Should resolve to DataModel.value field through type inference
        assertFalse(definitions.isEmpty(), "Should find field via cross-file type inference")
        assertEquals(modelFile.toString(), definitions[0].uri)
        assertEquals(1, definitions[0].range.start.line)
    }

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `infer type from cross-file field`() = runTest {
        // Setup: Repository has field of type from another file
        val entityFile = URI.create("file:///UserEntity.groovy")
        val entityContent = """
            class UserEntity {
                String username
                String email
            }
        """.trimIndent()

        val repoFile = URI.create("file:///UserRepository.groovy")
        val repoContent = """
            class UserRepository {
                UserEntity currentUser

                def getUsername() {
                    return currentUser.username
                }
            }
        """.trimIndent()

        // Compile both files
        compilationService.compile(entityFile, entityContent)
        compilationService.compile(repoFile, repoContent)

        // Act: Request definition at 'username' field access
        // Line 4, column 36 points to 'username' in currentUser.username
        val definitions = definitionProvider.provideDefinitions(repoFile.toString(), Position(4, 36)).toList()

        // Assert: Should resolve to UserEntity.username through field type
        assertFalse(definitions.isEmpty(), "Should find field via cross-file field type")
        assertEquals(entityFile.toString(), definitions[0].uri)
        assertEquals(1, definitions[0].range.start.line)
    }

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `generic type parameter resolution across files`() = runTest {
        // Setup: Generic Container class, concrete usage in another file
        val containerFile = URI.create("file:///Container.groovy")
        val containerContent = """
            class Container<T> {
                T value

                T getValue() { return value }
                void setValue(T val) { value = val }
            }
        """.trimIndent()

        val itemFile = URI.create("file:///Item.groovy")
        val itemContent = """
            class Item {
                String name
                int id
            }
        """.trimIndent()

        val usageFile = URI.create("file:///ContainerUsage.groovy")
        val usageContent = """
            class ContainerUsage {
                Container<Item> itemContainer

                def getItemName() {
                    def item = itemContainer.getValue()
                    return item.name
                }
            }
        """.trimIndent()

        // Compile all files
        compilationService.compile(containerFile, containerContent)
        compilationService.compile(itemFile, itemContent)
        compilationService.compile(usageFile, usageContent)

        // Act: Request definition at 'name' field on generic type
        // Line 5, column 28 points to 'name' in item.name
        val definitions = definitionProvider.provideDefinitions(usageFile.toString(), Position(5, 28)).toList()

        // Assert: Should resolve to Item.name through generic type resolution
        assertFalse(definitions.isEmpty(), "Should resolve field through generic type parameter")
        assertEquals(itemFile.toString(), definitions[0].uri)
        assertEquals(1, definitions[0].range.start.line)
    }

    // ==========================================================================
    // Category 4: Import Resolution
    // ==========================================================================

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `star import resolution`() = runTest {
        // Setup: Package with multiple classes, star import
        val class1File = URI.create("file:///models/Person.groovy")
        val class1Content = """
            package models

            class Person {
                String name
            }
        """.trimIndent()

        val class2File = URI.create("file:///models/Address.groovy")
        val class2Content = """
            package models

            class Address {
                String street
            }
        """.trimIndent()

        val usageFile = URI.create("file:///UserService.groovy")
        val usageContent = """
            import models.*

            class UserService {
                def createUser() {
                    return new Person()
                }
            }
        """.trimIndent()

        // Compile all files
        compilationService.compile(class1File, class1Content)
        compilationService.compile(class2File, class2Content)
        compilationService.compile(usageFile, usageContent)

        // Act: Request definition at 'Person' class usage
        // Line 4, column 28 points to 'Person'
        val definitions = definitionProvider.provideDefinitions(usageFile.toString(), Position(4, 28)).toList()

        // Assert: Should resolve through star import
        assertFalse(definitions.isEmpty(), "Should resolve class through star import")
        assertEquals(class1File.toString(), definitions[0].uri)
    }

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `static import resolution`() = runTest {
        // Setup: Utility class with static methods, static import
        val utilFile = URI.create("file:///StringUtils.groovy")
        val utilContent = """
            class StringUtils {
                static String capitalize(String input) {
                    return input ? input[0].toUpperCase() + input.substring(1) : ""
                }

                static String reverse(String input) {
                    return input?.reverse() ?: ""
                }
            }
        """.trimIndent()

        val usageFile = URI.create("file:///TextProcessor.groovy")
        val usageContent = """
            import static StringUtils.capitalize
            import static StringUtils.reverse

            class TextProcessor {
                def process(String text) {
                    def cap = capitalize(text)
                    def rev = reverse(text)
                    return [cap, rev]
                }
            }
        """.trimIndent()

        // Compile both files
        compilationService.compile(utilFile, utilContent)
        compilationService.compile(usageFile, usageContent)

        // Act: Request definition at 'capitalize' method call
        // Line 5, column 26 points to 'capitalize'
        val capDefs = definitionProvider.provideDefinitions(usageFile.toString(), Position(5, 26)).toList()

        // Assert: Should resolve through static import
        assertFalse(capDefs.isEmpty(), "Should resolve method through static import")
        assertEquals(utilFile.toString(), capDefs[0].uri)
        assertEquals(1, capDefs[0].range.start.line)

        // Act: Request definition at 'reverse' method call
        // Line 6, column 26 points to 'reverse'
        val revDefs = definitionProvider.provideDefinitions(usageFile.toString(), Position(6, 26)).toList()

        // Assert: Should resolve through static import
        assertFalse(revDefs.isEmpty(), "Should resolve method through static import")
        assertEquals(utilFile.toString(), revDefs[0].uri)
    }

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `aliased import resolution`() = runTest {
        // Setup: Class with alias import
        val originalFile = URI.create("file:///com/example/LongClassName.groovy")
        val originalContent = """
            package com.example

            class LongClassName {
                String value

                static void staticMethod() {
                    println "Static method"
                }
            }
        """.trimIndent()

        val usageFile = URI.create("file:///ShortUsage.groovy")
        val usageContent = """
            import com.example.LongClassName as Short

            class ShortUsage {
                def useShort() {
                    def instance = new Short()
                    return instance.value
                }
            }
        """.trimIndent()

        // Compile both files
        compilationService.compile(originalFile, originalContent)
        compilationService.compile(usageFile, usageContent)

        // Act: Request definition at 'Short' (aliased class) usage
        // Line 4, column 36 points to 'Short'
        val definitions = definitionProvider.provideDefinitions(usageFile.toString(), Position(4, 36)).toList()

        // Assert: Should resolve through alias to original class
        assertFalse(definitions.isEmpty(), "Should resolve class through alias import")
        assertEquals(originalFile.toString(), definitions[0].uri)
    }

    // ==========================================================================
    // Category 5: Completion Across Files
    // ==========================================================================

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `complete methods from cross-file type`() = runTest {
        // Setup: Service class in one file, usage in another
        val serviceFile = URI.create("file:///EmailService.groovy")
        val serviceContent = """
            class EmailService {
                void sendEmail(String to, String subject) {
                    println "Sending email to ${'$'}to"
                }

                void sendBulkEmail(List<String> recipients) {
                    println "Sending bulk email"
                }

                String getSmtpServer() {
                    return "smtp.example.com"
                }
            }
        """.trimIndent()

        val usageFile = URI.create("file:///NotificationService.groovy")
        val usageContent = """
            class NotificationService {
                EmailService emailService

                def notify(String recipient) {
                    emailService.
                }
            }
        """.trimIndent()

        // Compile both files
        compilationService.compile(serviceFile, serviceContent)
        compilationService.compile(usageFile, usageContent)

        // Act: Request completions at 'emailService.' (after the dot)
        // Line 4, column 33 is right after the dot
        val completions = CompletionProvider.getContextualCompletions(
            usageFile.toString(),
            4,
            33,
            compilationService,
            semanticResolver,
            usageContent,
        )

        // Assert: Should suggest methods from EmailService
        val labels = completions.map { it.label }
        assertTrue(labels.contains("sendEmail"), "Should suggest sendEmail method")
        assertTrue(labels.contains("sendBulkEmail"), "Should suggest sendBulkEmail method")
        assertTrue(labels.contains("getSmtpServer"), "Should suggest getSmtpServer method")
    }

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `complete inherited members from parent in different file`() = runTest {
        // Setup: Base class in one file, child class in another
        val baseFile = URI.create("file:///BaseController.groovy")
        val baseContent = """
            class BaseController {
                protected void render(String view) {
                    println "Rendering ${'$'}view"
                }

                protected void redirect(String url) {
                    println "Redirecting to ${'$'}url"
                }

                protected String getSession() {
                    return "session-id"
                }
            }
        """.trimIndent()

        val childFile = URI.create("file:///UserController.groovy")
        val childContent = """
            class UserController extends BaseController {
                def index() {
                    this.
                }
            }
        """.trimIndent()

        // Compile both files
        compilationService.compile(baseFile, baseContent)
        compilationService.compile(childFile, childContent)

        // Act: Request completions at 'this.' inside child class
        // Line 2, column 25 is right after the dot
        val completions = CompletionProvider.getContextualCompletions(
            childFile.toString(),
            2,
            25,
            compilationService,
            semanticResolver,
            childContent,
        )

        // Assert: Should suggest inherited methods from BaseController
        val labels = completions.map { it.label }
        assertTrue(labels.contains("render"), "Should suggest inherited render method")
        assertTrue(labels.contains("redirect"), "Should suggest inherited redirect method")
        assertTrue(labels.contains("getSession"), "Should suggest inherited getSession method")
    }

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `complete static members from other file`() = runTest {
        // Setup: Utility class with static members
        val utilFile = URI.create("file:///MathUtils.groovy")
        val utilContent = """
            class MathUtils {
                static final double PI = 3.14159
                static final double E = 2.71828

                static int add(int a, int b) {
                    return a + b
                }

                static int multiply(int a, int b) {
                    return a * b
                }
            }
        """.trimIndent()

        val usageFile = URI.create("file:///Calculator.groovy")
        val usageContent = """
            class Calculator {
                def calculate() {
                    MathUtils.
                }
            }
        """.trimIndent()

        // Compile both files
        compilationService.compile(utilFile, utilContent)
        compilationService.compile(usageFile, usageContent)

        // Act: Request completions at 'MathUtils.' (after the dot)
        // Line 2, column 30 is right after the dot
        val completions = CompletionProvider.getContextualCompletions(
            usageFile.toString(),
            2,
            30,
            compilationService,
            semanticResolver,
            usageContent,
        )

        // Assert: Should suggest static members from MathUtils
        val labels = completions.map { it.label }
        assertTrue(labels.contains("PI"), "Should suggest static field PI")
        assertTrue(labels.contains("E"), "Should suggest static field E")
        assertTrue(labels.contains("add"), "Should suggest static method add")
        assertTrue(labels.contains("multiply"), "Should suggest static method multiply")
    }

    // ==========================================================================
    // Additional Edge Cases and Complex Scenarios
    // ==========================================================================

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `resolve through multiple file dependencies`() = runTest {
        // Setup: Chain of dependencies: A -> B -> C
        val fileC = URI.create("file:///CoreModel.groovy")
        val contentC = """
            class CoreModel {
                String coreValue
            }
        """.trimIndent()

        val fileB = URI.create("file:///MiddleLayer.groovy")
        val contentB = """
            class MiddleLayer {
                CoreModel model

                def getCoreValue() {
                    return model.coreValue
                }
            }
        """.trimIndent()

        val fileA = URI.create("file:///TopLayer.groovy")
        val contentA = """
            class TopLayer {
                MiddleLayer middle

                def processValue() {
                    def value = middle.getCoreValue()
                    return value
                }
            }
        """.trimIndent()

        // Compile all files
        compilationService.compile(fileC, contentC)
        compilationService.compile(fileB, contentB)
        compilationService.compile(fileA, contentA)

        // Act: Request definition at 'getCoreValue' in TopLayer
        // Line 4, column 38 points to 'getCoreValue'
        val definitions = definitionProvider.provideDefinitions(fileA.toString(), Position(4, 38)).toList()

        // Assert: Should resolve through dependency chain
        assertFalse(definitions.isEmpty(), "Should resolve through multiple file dependencies")
        assertEquals(fileB.toString(), definitions[0].uri)
    }

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `resolve inner class from different file`() = runTest {
        // Setup: Outer class with inner class, usage in another file
        val outerFile = URI.create("file:///OuterClass.groovy")
        val outerContent = """
            class OuterClass {
                String outerField

                static class InnerClass {
                    String innerField
                }
            }
        """.trimIndent()

        val usageFile = URI.create("file:///InnerUsage.groovy")
        val usageContent = """
            class InnerUsage {
                def createInner() {
                    return new OuterClass.InnerClass()
                }
            }
        """.trimIndent()

        // Compile both files
        compilationService.compile(outerFile, outerContent)
        compilationService.compile(usageFile, usageContent)

        // Act: Request definition at 'InnerClass'
        // Line 2, column 41 points to 'InnerClass'
        val definitions = definitionProvider.provideDefinitions(usageFile.toString(), Position(2, 41)).toList()

        // Assert: Should resolve to inner class definition
        assertFalse(definitions.isEmpty(), "Should resolve inner class from different file")
        assertEquals(outerFile.toString(), definitions[0].uri)
    }

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `workspace-wide class resolution without explicit import`() = runTest {
        // Setup: Classes in same package, no explicit import needed
        val model1File = URI.create("file:///com/example/User.groovy")
        val model1Content = """
            package com.example

            class User {
                String name
            }
        """.trimIndent()

        val model2File = URI.create("file:///com/example/UserService.groovy")
        val model2Content = """
            package com.example

            class UserService {
                def getUser() {
                    return new User()
                }
            }
        """.trimIndent()

        // Compile both files
        compilationService.compile(model1File, model1Content)
        compilationService.compile(model2File, model2Content)

        // Act: Request definition at 'User' (no import, same package)
        // Line 4, column 28 points to 'User'
        val definitions = definitionProvider.provideDefinitions(model2File.toString(), Position(4, 28)).toList()

        // Assert: Should resolve within same package without import
        assertFalse(definitions.isEmpty(), "Should resolve class in same package without import")
        assertEquals(model1File.toString(), definitions[0].uri)
    }

    @Test
    @Disabled("Phase -2: TDD - Expected to FAIL initially")
    fun `resolve overridden method to both parent and child implementations`() = runTest {
        // Setup: Parent method, child overrides it
        val parentFile = URI.create("file:///Animal.groovy")
        val parentContent = """
            class Animal {
                String makeSound() {
                    return "Some sound"
                }
            }
        """.trimIndent()

        val childFile = URI.create("file:///Dog.groovy")
        val childContent = """
            class Dog extends Animal {
                @Override
                String makeSound() {
                    return "Woof"
                }
            }
        """.trimIndent()

        val usageFile = URI.create("file:///AnimalTest.groovy")
        val usageContent = """
            class AnimalTest {
                def test() {
                    Dog dog = new Dog()
                    return dog.makeSound()
                }
            }
        """.trimIndent()

        // Compile all files
        compilationService.compile(parentFile, parentContent)
        compilationService.compile(childFile, childContent)
        compilationService.compile(usageFile, usageContent)

        // Act: Request definition at 'makeSound' call on Dog instance
        // Line 3, column 31 points to 'makeSound'
        val definitions = definitionProvider.provideDefinitions(usageFile.toString(), Position(3, 31)).toList()

        // Assert: Should resolve to child's overridden method (type-aware resolution)
        assertFalse(definitions.isEmpty(), "Should resolve overridden method")
        assertNotNull(
            definitions.find { it.uri == childFile.toString() },
            "Should include child class implementation",
        )
    }
}
