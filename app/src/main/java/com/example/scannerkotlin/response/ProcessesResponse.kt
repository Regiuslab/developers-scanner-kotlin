package com.example.scannerkotlin.response

import com.google.gson.annotations.SerializedName

data class ProcessesResponse(
    @SerializedName("result")
    val result: ResultData?,

    @SerializedName("total")
    val total: Int,


) {


    data class ResultData(
        @SerializedName("items")
        val items: List<ItemData>?
    )


    data class ItemData(
        @SerializedName("id")
        val id: Int,

        @SerializedName("xmlId")
        val xmlId: String?,

        @SerializedName("title")
        val title: String?,

        @SerializedName("createdBy")
        val createdBy: Int,

        @SerializedName("updatedBy")
        val updatedBy: Int,

        @SerializedName("movedBy")
        val movedBy: Int,

        @SerializedName("createdTime")
        val createdTime: String?,

        @SerializedName("updatedTime")
        val updatedTime: String?,

        @SerializedName("movedTime")
        val movedTime: String?,

        @SerializedName("categoryId")
        val categoryId: Int,

        @SerializedName("opened")
        val opened: String?,

        @SerializedName("stageId")
        val stageId: String?,

        @SerializedName("previousStageId")
        val previousStageId: String?,

        @SerializedName("begindate")
        val begindate: String?,

        @SerializedName("closedate")
        val closedate: String?,

        @SerializedName("companyId")
        val companyId: Int,

        @SerializedName("contactId")
        val contactId: Int,

        @SerializedName("opportunity")
        val opportunity: Double,

        @SerializedName("isManualOpportunity")
        val isManualOpportunity: String?,

        @SerializedName("taxValue")
        val taxValue: Double,

        @SerializedName("currencyId")
        val currencyId: String?,

        @SerializedName("mycompanyId")
        val mycompanyId: Int,

        @SerializedName("sourceId")
        val sourceId: String?,

        @SerializedName("sourceDescription")
        val sourceDescription: String?,

        @SerializedName("webformId")
        val webformId: Int,

        @SerializedName("ufCrm10_1737917868765")
        val ufCrm10Property1: String?,

        @SerializedName("ufCrm10_1737917903883")
        val ufCrm10Property2: String?,

        @SerializedName("ufCrm10_1747122173398")
        val ufCrm10Property3: String?,

        @SerializedName("ufCrm9_1737392983594")
        val ufCrm9Property1: String?,

        @SerializedName("ufCrmSourceDescription")
        val ufCrmSourceDescription: String?,

        @SerializedName("ufCrmSourceId")
        val ufCrmSourceId: String?,

        @SerializedName("ufCrm11_1737195282")
        val ufCrm11Property1: String?,

        @SerializedName("ufCrmObserverIds")
        val ufCrmObserverIds: List<Any>?,

        @SerializedName("ufCrm11_1737195384")
        val ufCrm11Property2: String?,

        @SerializedName("ufCrm9_1736855162574")
        val ufCrm9Property2: String?,

        @SerializedName("ufCrm11_1737721248921")
        val ufCrm11Property3: String?,

        @SerializedName("ufCrm_PARENT_ID_1074")
        val ufCrmParentId1074: Int?,

        @SerializedName("ufCrm_PARENT_ID_1066")
        val ufCrmParentId1066: Int?,

        @SerializedName("ufCrm9_1737910152483")
        val ufCrm9Property3: String?,

        @SerializedName("ufCrm11_1737482625923")
        val ufCrm11Property4: String?,

        @SerializedName("ufCrm11_1737194411167")
        val ufCrm11Property5: String?,

        @SerializedName("assignedById")
        val assignedById: Int,

        @SerializedName("lastActivityBy")
        val lastActivityBy: Int,

        @SerializedName("lastActivityTime")
        val lastActivityTime: String?,

        @SerializedName("utmSource")
        val utmSource: String?,

        @SerializedName("utmMedium")
        val utmMedium: String?,

        @SerializedName("utmCampaign")
        val utmCampaign: String?,

        @SerializedName("utmContent")
        val utmContent: String?,

        @SerializedName("utmTerm")
        val utmTerm: String?,

        @SerializedName("observers")
        val observers: List<Any>?,

        @SerializedName("contactIds")
        val contactIds: List<Any>?,

        @SerializedName("entityTypeId")
        val entityTypeId: Int
    )
}