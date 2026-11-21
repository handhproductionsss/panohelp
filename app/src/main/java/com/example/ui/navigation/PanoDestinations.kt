package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
object SessionsRoute

@Serializable
data class CaptureRoute(val sceneId: String)
