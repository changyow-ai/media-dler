package com.changyow.mediadler.core.model

import kotlinx.serialization.Serializable

/** Frozen when queued. Provider markers locate candidates; only confirmed content authorizes removal. */
@Serializable enum class AdRemovalPolicy { OFF, CONFIRMED_ONLY, WEIGHTED }
