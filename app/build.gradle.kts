import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

fun loadProps(file: java.io.File): Properties {
    val props = Properties()
    if (file.exists()) {
        file.inputStream().use { props.load(it) }
    }
    return props
}

val localProps = loadProps(rootProject.file("local.properties"))
val bitrixProps = loadProps(rootProject.file("bitrix.properties"))

fun bitrixProp(key: String, default: String): String {
    return localProps.getProperty(key)
        ?: bitrixProps.getProperty(key)
        ?: default
}

android {
    namespace = "com.example.scannerkotlin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.scannerkotlin"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Defaults = current izocom portal. Override via bitrix.properties or local.properties.
        val bitrixHost = bitrixProp("bitrix.host", "bitrix.izocom.by")
        val bitrixUserId = bitrixProp("bitrix.rest.userId", "1")
        val webhookCatalog = bitrixProp("bitrix.webhook.catalog", "o2deu7wx7zfl3ib4")
        val webhookBarcodeScan = bitrixProp("bitrix.webhook.barcode.scan", "c953o6imkob2gpwd")
        val webhookBarcodeComing = bitrixProp("bitrix.webhook.barcode.coming", "sh1lchx64vrzcor6")
        val iblockPin = bitrixProp("bitrix.iblock.pin", "18")
        val iblockProducts = bitrixProp("bitrix.iblock.products", "14")
        val iblockOffers = bitrixProp("bitrix.iblock.offers", "15")
        val roleStorekeeper = bitrixProp("bitrix.role.storekeeper", "75")
        val roleOtk = bitrixProp("bitrix.role.otk", "76")
        val tenantName = bitrixProp("bitrix.tenant.name", "izocom")
        val concurrencyMode = bitrixProp("bitrix.concurrency.mode", "LEGACY")

        buildConfigField("String", "BITRIX_HOST", "\"$bitrixHost\"")
        buildConfigField("String", "BITRIX_REST_USER_ID", "\"$bitrixUserId\"")
        buildConfigField("String", "BITRIX_WEBHOOK_CATALOG", "\"$webhookCatalog\"")
        buildConfigField("String", "BITRIX_WEBHOOK_BARCODE_SCAN", "\"$webhookBarcodeScan\"")
        buildConfigField("String", "BITRIX_WEBHOOK_BARCODE_COMING", "\"$webhookBarcodeComing\"")
        buildConfigField("int", "BITRIX_IBLOCK_PIN", iblockPin)
        buildConfigField("int", "BITRIX_IBLOCK_PRODUCTS", iblockProducts)
        buildConfigField("int", "BITRIX_IBLOCK_OFFERS", iblockOffers)
        buildConfigField("String", "BITRIX_ROLE_STOREKEEPER", "\"$roleStorekeeper\"")
        buildConfigField("String", "BITRIX_ROLE_OTK", "\"$roleOtk\"")
        buildConfigField("String", "BITRIX_TENANT_NAME", "\"$tenantName\"")
        buildConfigField("String", "BITRIX_CONCURRENCY_MODE", "\"$concurrencyMode\"")
        buildConfigField("String", "DEVELOPER_WEBSITE", "\"https://regiuslab.by/\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.retrofit)
    implementation(libs.gson)
    implementation(libs.retrofit.gson.converter)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
