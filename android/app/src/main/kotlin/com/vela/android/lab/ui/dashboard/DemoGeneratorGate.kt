package com.vela.android.lab.ui.dashboard

import com.vela.android.lab.BuildConfig

/**
 * Phase 3.a.1-D.1: the only production read of the build flag for the demo generators.
 *
 * The demo generators persist deterministic synthetic bars into the operational `market_bars_1m` table through a
 * `REPLACE` insert. A Release build must not reach that write, so Release closes this gate. Owner decision: option (a),
 * debug-only generators. No runtime preference, endpoint, label or package name can open it.
 *
 * DEBUG_DEMO_PERSISTENCE_REMAINS_LEGACY_CONTAMINATING_BY_DESIGN: in Debug builds the generators still write into the
 * legacy table. Their rows are never training eligible, never execution authoritative, and never imported into a
 * future Learning Dataset. The table stays quarantined as a whole. Debug rows do not gain trustworthy provenance.
 */
internal fun demoGeneratorsEnabledForThisBuild(): Boolean = BuildConfig.DEBUG
