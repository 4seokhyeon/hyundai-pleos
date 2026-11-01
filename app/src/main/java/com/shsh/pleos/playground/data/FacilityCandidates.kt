package com.shsh.pleos.playground.data

data class CachedFacility(val name: String,val address: String,val latitude: Double,val longitude: Double,
    val referenceDate: String,val hours: String?=null,val fee: String?=null)
data class RestroomCandidate(val restroom: CachedFacility,val distanceMeters: Double,val parking: CachedFacility?,val parkingDistanceMeters: Double?)
