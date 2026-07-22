package com.vela.android.lab

import android.app.Application
import com.vela.android.lab.data.market.FeatureEngine
import com.vela.android.lab.data.market.OneMinuteBarAggregator
import com.vela.android.lab.data.market.SignalEngine
import com.vela.android.lab.data.market.source.alpaca.AlpacaCredentialsProvider
import com.vela.android.lab.data.market.source.alpaca.AlpacaStockMarketDataClient
import com.vela.android.lab.data.market.source.alpaca.AlpacaTestStreamMarketDataClient
import com.vela.android.lab.data.market.source.alpaca.AlpacaWebSocketFactory
import com.vela.android.lab.data.market.source.alpaca.BuildConfigAlpacaCredentialsProvider
import com.vela.android.lab.data.market.source.alpaca.CompositeAlpacaCredentialsProvider
import com.vela.android.lab.data.market.source.alpaca.EncryptedPrefsAlpacaCredentialsStore
import com.vela.android.lab.data.market.source.alpaca.OkHttpAlpacaWebSocketFactory
import com.vela.android.lab.data.market.source.alpaca.SecureAlpacaCredentialsProvider
import com.vela.android.lab.data.market.source.alpaca.SecureAlpacaCredentialsStore
import com.vela.android.lab.data.market.price.MarketPriceFreshnessPolicy
import com.vela.android.lab.data.market.price.MarketPriceSnapshotProvider
import com.vela.android.lab.data.market.tick.MarketTickBuffer
import com.vela.android.lab.data.paper.AlpacaHttpClient
import com.vela.android.lab.data.paper.AlpacaPaperReadOnlyClient
import com.vela.android.lab.data.paper.OkHttpAlpacaHttpClient
import com.vela.android.lab.data.paper.preflight.PaperOrderDryRunAuditRepository
import com.vela.android.lab.data.paper.preflight.PaperOrderPayloadPreviewRepository
import com.vela.android.lab.data.paper.preflight.PaperOrderPreflightEngine
import com.vela.android.lab.data.paper.submit.AlpacaPaperOrderSubmitHttpClient
import com.vela.android.lab.data.paper.submit.OkHttpAlpacaPaperOrderSubmitHttpClient
import com.vela.android.lab.data.paper.submit.PaperManualExecutionFeatureGate
import com.vela.android.lab.data.paper.submit.PaperManualOrderSubmitClient
import com.vela.android.lab.data.paper.submit.PaperManualSubmitExecutor
import com.vela.android.lab.data.paper.submit.PaperManualSubmitGate
import com.vela.android.lab.data.paper.submit.PaperManualSubmitTokenStore
import com.vela.android.lab.data.paper.submit.PaperOrderSubmitAuditRepository
import com.vela.android.lab.state.AppState
import com.vela.android.lab.data.pipeline.AlpacaTestStreamPipelineBridge
import com.vela.android.lab.data.pipeline.OfflineMarketPipelineCoordinator
import com.vela.android.lab.data.repository.FeatureRepository
import com.vela.android.lab.data.repository.JournalRepository
import com.vela.android.lab.data.repository.MarketDataRepository
import com.vela.android.lab.data.repository.SignalRepository
import com.vela.android.lab.data.watchlist.SharedPrefsWatchlistStore
import com.vela.android.lab.data.watchlist.WatchlistRepository
import com.vela.android.lab.data.watchlist.WatchlistStore
import com.vela.android.lab.db.room.VelaDatabase
import com.vela.android.lab.ui.settings.VelaPreferencesStore

/**
 * Process-scoped DI graph for the Android lab.
 *
 * Phase 2.c.1 wiring:
 *  - [alpacaCredentialsStore] is the Android Keystore-backed
 *    `EncryptedSharedPreferences` instance. **It never opens a
 *    network connection on its own.** The store stays empty until
 *    the user saves credentials via the in-app settings card.
 *  - [alpacaCredentialsProvider] is a composite: it tries the
 *    secure store first, then falls back to the BuildConfig
 *    developer-only path. Release builds have empty BuildConfig
 *    fields, so the composite returns null unless the user has
 *    explicitly saved credentials.
 *  - The Phase 1.e offline dashboard never touches any of these.
 */
class VelaLabApplication : Application() {

    val database: VelaDatabase by lazy {
        VelaDatabase.create(this)
    }

    val marketDataRepository: MarketDataRepository by lazy {
        MarketDataRepository(database.marketBarDao())
    }


    val visualPreferencesStore: VelaPreferencesStore by lazy {
        VelaPreferencesStore(this)
    }
    val featureRepository: FeatureRepository by lazy {
        FeatureRepository(database.featureDao())
    }

    val signalRepository: SignalRepository by lazy {
        SignalRepository(database.signalDao())
    }

    val journalRepository: JournalRepository by lazy {
        JournalRepository(database.journalDao())
    }

    val barAggregator: OneMinuteBarAggregator by lazy {
        OneMinuteBarAggregator()
    }

    val featureEngine: FeatureEngine by lazy {
        FeatureEngine(barAggregator)
    }

    val signalEngine: SignalEngine by lazy {
        SignalEngine(featureEngine)
    }

    val pipelineCoordinator: OfflineMarketPipelineCoordinator by lazy {
        OfflineMarketPipelineCoordinator(
            barAggregator = barAggregator,
            featureEngine = featureEngine,
            signalEngine = signalEngine,
            marketDataRepository = marketDataRepository,
            featureRepository = featureRepository,
            signalRepository = signalRepository,
            journalRepository = journalRepository,
        )
    }

    // --- Phase 2.c.1: secure credentials + read-only Alpaca test stream

    val alpacaCredentialsStore: SecureAlpacaCredentialsStore by lazy {
        EncryptedPrefsAlpacaCredentialsStore(this)
    }

    val alpacaCredentialsProvider: AlpacaCredentialsProvider by lazy {
        CompositeAlpacaCredentialsProvider(
            // Primary: in-app saved credentials.
            SecureAlpacaCredentialsProvider(alpacaCredentialsStore),
            // Fallback: BuildConfig-from-local.properties (debug only;
            // release builds force empty values regardless).
            BuildConfigAlpacaCredentialsProvider.fromBuildConfig(),
        )
    }

    val alpacaWebSocketFactory: AlpacaWebSocketFactory by lazy {
        OkHttpAlpacaWebSocketFactory()
    }

    val alpacaTestStreamClient: AlpacaTestStreamMarketDataClient by lazy {
        AlpacaTestStreamMarketDataClient(
            credentialsProvider = alpacaCredentialsProvider,
            webSocketFactory = alpacaWebSocketFactory,
        )
    }

    /**
     * Phase 2.d bridge — wires the read-only Alpaca test stream
     * into the existing offline pipeline coordinator. Inert until
     * the user taps **Test Alpaca Market Data** on the dashboard,
     * at which point [AlpacaTestStreamPipelineBridge.start] is
     * called from the ViewModel scope.
     */
    val alpacaTestStreamPipelineBridge: AlpacaTestStreamPipelineBridge by lazy {
        AlpacaTestStreamPipelineBridge(
            client = alpacaTestStreamClient,
            coordinator = pipelineCoordinator,
        )
    }

    // --- Phase 2.e: read-only Alpaca real-stock IEX stream

    val alpacaStockClient: AlpacaStockMarketDataClient by lazy {
        AlpacaStockMarketDataClient(
            credentialsProvider = alpacaCredentialsProvider,
            webSocketFactory = alpacaWebSocketFactory,
        )
    }

    /**
     * Phase 2.e bridge — wires the read-only Alpaca IEX stream into
     * the same offline pipeline coordinator. A separate bridge
     * instance per client; both share the coordinator/repositories.
     */
    val alpacaStockPipelineBridge: AlpacaTestStreamPipelineBridge by lazy {
        AlpacaTestStreamPipelineBridge(
            client = alpacaStockClient,
            coordinator = pipelineCoordinator,
        )
    }

    // --- Phase 2.g: read-only watchlist

    val watchlistStore: WatchlistStore by lazy {
        SharedPrefsWatchlistStore(this)
    }

    val watchlistRepository: WatchlistRepository by lazy {
        WatchlistRepository(watchlistStore)
    }

    // --- Phase 2.i: read-only tick / quote diagnostics buffer

    val marketTickBuffer: MarketTickBuffer by lazy {
        MarketTickBuffer()
    }

    // --- Phase 2.k: read-only Paper Trading API boundary

    val alpacaHttpClient: AlpacaHttpClient by lazy {
        OkHttpAlpacaHttpClient()
    }

    val alpacaPaperReadOnlyClient: AlpacaPaperReadOnlyClient by lazy {
        AlpacaPaperReadOnlyClient(
            credentialsProvider = alpacaCredentialsProvider,
            httpClient = alpacaHttpClient,
        )
    }

    // --- Phase 2.m: read-only dry-run order preflight (no execution surface)

    /**
     * Default-initialised [AppState] with `realModeLocked = true`
     * and `mode = READ_ONLY`. Shared across the dashboard VMs so a
     * future REAL-mode unlock attempt would fail the Phase 2.m
     * preflight engine's `RealLocked` guard before producing an
     * `ALLOWED_DRY_RUN` outcome.
     */
    val appState: AppState by lazy { AppState() }

    val paperOrderPreflightEngine: PaperOrderPreflightEngine by lazy {
        PaperOrderPreflightEngine()
    }

    // --- Phase 2.n: append-only local dry-run audit trail

    val paperOrderDryRunAuditRepository: PaperOrderDryRunAuditRepository by lazy {
        PaperOrderDryRunAuditRepository(database.paperOrderDryRunAuditDao())
    }

    // --- Phase 2.q: append-only local payload-preview review queue

    val paperOrderPayloadPreviewRepository: PaperOrderPayloadPreviewRepository by lazy {
        PaperOrderPayloadPreviewRepository(database.paperOrderPayloadPreviewDao())
    }

    // --- Phase 2.o: local-only market price snapshot + freshness gate

    val marketPriceFreshnessPolicy: MarketPriceFreshnessPolicy by lazy {
        MarketPriceFreshnessPolicy()
    }

    val marketPriceSnapshotProvider: MarketPriceSnapshotProvider by lazy {
        MarketPriceSnapshotProvider(
            tickBuffer = marketTickBuffer,
            marketDataRepository = marketDataRepository,
            freshnessPolicy = marketPriceFreshnessPolicy,
        )
    }

    // --- Phase 2.v: default-off, one-shot manual Paper submit boundary

    val paperManualExecutionFeatureGate: PaperManualExecutionFeatureGate by lazy {
        PaperManualExecutionFeatureGate(
            compileTimeEnabled = BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED,
        )
    }

    val paperManualSubmitTokenStore: PaperManualSubmitTokenStore by lazy {
        PaperManualSubmitTokenStore()
    }

    private val alpacaPaperOrderSubmitHttpClient: AlpacaPaperOrderSubmitHttpClient by lazy {
        OkHttpAlpacaPaperOrderSubmitHttpClient(
            credentialsProvider = alpacaCredentialsProvider,
        )
    }

    private val paperManualOrderSubmitClient: PaperManualOrderSubmitClient by lazy {
        PaperManualOrderSubmitClient(alpacaPaperOrderSubmitHttpClient)
    }

    val paperManualSubmitGate: PaperManualSubmitGate by lazy {
        PaperManualSubmitGate(paperManualExecutionFeatureGate)
    }

    val paperOrderSubmitAuditRepository: PaperOrderSubmitAuditRepository by lazy {
        PaperOrderSubmitAuditRepository(database.paperOrderSubmitAuditDao())
    }

    val paperManualSubmitExecutor: PaperManualSubmitExecutor by lazy {
        PaperManualSubmitExecutor(
            gate = paperManualSubmitGate,
            tokenStore = paperManualSubmitTokenStore,
            submitClient = paperManualOrderSubmitClient,
            auditRepository = paperOrderSubmitAuditRepository,
            finalPriceSnapshotProvider = marketPriceSnapshotProvider::snapshotFor,
        )
    }
}
