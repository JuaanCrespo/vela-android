package com.vela.android.lab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vela.android.lab.ui.candles.CandlesViewModel
import com.vela.android.lab.ui.dashboard.AlpacaStockStreamViewModel
import com.vela.android.lab.ui.dashboard.AlpacaTestStreamViewModel
import com.vela.android.lab.ui.dashboard.MarketHistoryViewModel
import com.vela.android.lab.ui.dashboard.OfflineDashboardScreen
import com.vela.android.lab.ui.dashboard.OfflineDashboardViewModel
import com.vela.android.lab.ui.dashboard.PaperAccountViewModel
import com.vela.android.lab.ui.dashboard.PaperOrderDryRunAuditViewModel
import com.vela.android.lab.ui.dashboard.PaperOrderPayloadPreviewQueueViewModel
import com.vela.android.lab.ui.dashboard.PaperOrderPreflightViewModel
import com.vela.android.lab.ui.dashboard.PaperManualSubmitViewModel
import com.vela.android.lab.ui.dashboard.PaperPortfolioRiskViewModel
import com.vela.android.lab.ui.dashboard.WatchlistViewModel
import com.vela.android.lab.ui.history.PaperOrderHistoryViewModel
import com.vela.android.lab.ui.settings.VelaPreferencesViewModel
import com.vela.android.lab.ui.theme.VelaLabTheme
import com.vela.android.lab.ui.positions.PositionReconciliationRoute
import com.vela.android.lab.ui.positions.PositionReconciliationViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: OfflineDashboardViewModel by viewModels { dashboardFactory() }

    /**
     * Phase 2.c.1: the "Alpaca Paper Credentials" card on the
     * dashboard is wired here. The card is gated by
     * [BuildConfig.DEBUG] inside `OfflineDashboardScreen`, so the
     * release UI shows only the Phase 1.e offline dashboard.
     */
    private val alpacaViewModel: AlpacaTestStreamViewModel by viewModels { alpacaFactory() }

    private val alpacaStockViewModel: AlpacaStockStreamViewModel by viewModels { alpacaStockFactory() }

    private val watchlistViewModel: WatchlistViewModel by viewModels { watchlistFactory() }

    private val historyViewModel: MarketHistoryViewModel by viewModels { historyFactory() }

    private val paperOrderHistoryViewModel: PaperOrderHistoryViewModel by viewModels {
        paperOrderHistoryFactory()
    }

    private val paperAccountViewModel: PaperAccountViewModel by viewModels { paperAccountFactory() }

    private val paperPortfolioRiskViewModel: PaperPortfolioRiskViewModel by viewModels { paperPortfolioRiskFactory() }

    private val paperOrderPreflightViewModel: PaperOrderPreflightViewModel by viewModels { paperOrderPreflightFactory() }

    private val paperOrderDryRunAuditViewModel: PaperOrderDryRunAuditViewModel by viewModels { paperOrderDryRunAuditFactory() }

    private val paperOrderPayloadPreviewQueueViewModel: PaperOrderPayloadPreviewQueueViewModel by viewModels {
        paperOrderPayloadPreviewQueueFactory()
    }

    private val paperManualSubmitViewModel: PaperManualSubmitViewModel by viewModels {
        paperManualSubmitFactory()
    }

    private val candlesViewModel: CandlesViewModel by viewModels { candlesFactory() }

    private val preferencesViewModel: VelaPreferencesViewModel by viewModels { preferencesFactory() }

    private val positionsViewModel: PositionReconciliationViewModel by viewModels {
        viewModelFactory { initializer {
            PositionReconciliationViewModel((application as VelaLabApplication).positionReconciliationStore)
        } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VelaLabTheme {
                OfflineDashboardScreen(
                    viewModel = viewModel,
                    alpacaViewModel = if (BuildConfig.DEBUG) alpacaViewModel else null,
                    alpacaStockViewModel = if (BuildConfig.DEBUG) alpacaStockViewModel else null,
                    watchlistViewModel = if (BuildConfig.DEBUG) watchlistViewModel else null,
                    historyViewModel = if (BuildConfig.DEBUG) historyViewModel else null,
                    paperOrderHistoryViewModel = paperOrderHistoryViewModel,
                    paperAccountViewModel = if (BuildConfig.DEBUG) paperAccountViewModel else null,
                    paperPortfolioRiskViewModel = if (BuildConfig.DEBUG) paperPortfolioRiskViewModel else null,
                    paperOrderPreflightViewModel = if (BuildConfig.DEBUG) paperOrderPreflightViewModel else null,
                    paperOrderDryRunAuditViewModel = if (BuildConfig.DEBUG) paperOrderDryRunAuditViewModel else null,
                    paperOrderPayloadPreviewQueueViewModel =
                        if (BuildConfig.DEBUG) paperOrderPayloadPreviewQueueViewModel else null,
                    paperManualSubmitViewModel =
                        if (BuildConfig.DEBUG) paperManualSubmitViewModel else null,
                    candlesViewModel = candlesViewModel,
                    preferencesViewModel = preferencesViewModel,
                    // The lazy ViewModel is accessed only when this destination is composed.
                    positionsContent = { padding -> PositionReconciliationRoute(positionsViewModel, padding) },
                )
            }
        }
    }

    private fun dashboardFactory(): ViewModelProvider.Factory {
        val app = application as VelaLabApplication
        return viewModelFactory {
            initializer {
                OfflineDashboardViewModel(
                    coordinator = app.pipelineCoordinator,
                    marketDataRepository = app.marketDataRepository,
                    featureRepository = app.featureRepository,
                    signalRepository = app.signalRepository,
                    journalRepository = app.journalRepository,
                )
            }
        }
    }

    private fun alpacaFactory(): ViewModelProvider.Factory {
        val app = application as VelaLabApplication
        return viewModelFactory {
            initializer {
                AlpacaTestStreamViewModel(
                    client = app.alpacaTestStreamClient,
                    credentialsStore = app.alpacaCredentialsStore,
                    pipelineBridge = app.alpacaTestStreamPipelineBridge,
                )
            }
        }
    }

    private fun alpacaStockFactory(): ViewModelProvider.Factory {
        val app = application as VelaLabApplication
        return viewModelFactory {
            initializer {
                AlpacaStockStreamViewModel(
                    client = app.alpacaStockClient,
                    credentialsStore = app.alpacaCredentialsStore,
                    pipelineBridge = app.alpacaStockPipelineBridge,
                    tickBuffer = app.marketTickBuffer,
                )
            }
        }
    }

    private fun watchlistFactory(): ViewModelProvider.Factory {
        val app = application as VelaLabApplication
        return viewModelFactory {
            initializer {
                WatchlistViewModel(
                    repository = app.watchlistRepository,
                    pipelineBridge = app.alpacaStockPipelineBridge,
                )
            }
        }
    }

    private fun historyFactory(): ViewModelProvider.Factory {
        val app = application as VelaLabApplication
        return viewModelFactory {
            initializer {
                MarketHistoryViewModel(
                    watchlistRepository = app.watchlistRepository,
                    marketDataRepository = app.marketDataRepository,
                    featureRepository = app.featureRepository,
                    signalRepository = app.signalRepository,
                    journalRepository = app.journalRepository,
                )
            }
        }
    }

    private fun paperOrderHistoryFactory(): ViewModelProvider.Factory {
        val app = application as VelaLabApplication
        return viewModelFactory {
            initializer {
                PaperOrderHistoryViewModel(repository = app.paperOrderHistoryRepository)
            }
        }
    }

    private fun paperAccountFactory(): ViewModelProvider.Factory {
        val app = application as VelaLabApplication
        return viewModelFactory {
            initializer {
                PaperAccountViewModel(
                    client = app.alpacaPaperReadOnlyClient,
                    credentialsStore = app.alpacaCredentialsStore,
                )
            }
        }
    }

    private fun paperPortfolioRiskFactory(): ViewModelProvider.Factory {
        val app = application as VelaLabApplication
        return viewModelFactory {
            initializer {
                PaperPortfolioRiskViewModel(
                    client = app.alpacaPaperReadOnlyClient,
                    credentialsStore = app.alpacaCredentialsStore,
                    watchlistRepository = app.watchlistRepository,
                    marketDataRepository = app.marketDataRepository,
                    signalRepository = app.signalRepository,
                )
            }
        }
    }

    private fun paperOrderPreflightFactory(): ViewModelProvider.Factory {
        val app = application as VelaLabApplication
        return viewModelFactory {
            initializer {
                PaperOrderPreflightViewModel(
                    engine = app.paperOrderPreflightEngine,
                    client = app.alpacaPaperReadOnlyClient,
                    credentialsStore = app.alpacaCredentialsStore,
                    watchlistRepository = app.watchlistRepository,
                    marketDataRepository = app.marketDataRepository,
                    signalRepository = app.signalRepository,
                    appState = app.appState,
                    auditRepository = app.paperOrderDryRunAuditRepository,
                    onAuditSaved = { paperOrderDryRunAuditViewModel.refreshNow() },
                    priceSnapshotProvider = app.marketPriceSnapshotProvider,
                    payloadPreviewRepository = app.paperOrderPayloadPreviewRepository,
                    onPayloadPreviewSaved = {
                        paperOrderPayloadPreviewQueueViewModel.refreshNow()
                    },
                )
            }
        }
    }

    private fun paperOrderDryRunAuditFactory(): ViewModelProvider.Factory {
        val app = application as VelaLabApplication
        return viewModelFactory {
            initializer {
                PaperOrderDryRunAuditViewModel(
                    repository = app.paperOrderDryRunAuditRepository,
                )
            }
        }
    }

    private fun paperOrderPayloadPreviewQueueFactory(): ViewModelProvider.Factory {
        val app = application as VelaLabApplication
        return viewModelFactory {
            initializer {
                PaperOrderPayloadPreviewQueueViewModel(
                    repository = app.paperOrderPayloadPreviewRepository,
                )
            }
        }
    }

    private fun paperManualSubmitFactory(): ViewModelProvider.Factory {
        val app = application as VelaLabApplication
        return viewModelFactory {
            initializer {
                PaperManualSubmitViewModel(
                    featureGate = app.paperManualExecutionFeatureGate,
                    gate = app.paperManualSubmitGate,
                    tokenStore = app.paperManualSubmitTokenStore,
                    executor = app.paperManualSubmitExecutor,
                    readOnlyClient = app.alpacaPaperReadOnlyClient,
                    credentialsStore = app.alpacaCredentialsStore,
                    priceSnapshotProvider = app.marketPriceSnapshotProvider,
                    previewRepository = app.paperOrderPayloadPreviewRepository,
                    appState = app.appState,
                    orderStatusClient = app.alpacaPaperOrderStatusReadOnlyClient,
                    orderStatusTrackerRepository = app.paperOrderStatusTrackerRepository,
                )
            }
        }
    }

    private fun candlesFactory(): ViewModelProvider.Factory {
        val app = application as VelaLabApplication
        return viewModelFactory {
            initializer {
                CandlesViewModel(
                    watchlistRepository = app.watchlistRepository,
                    marketDataRepository = app.marketDataRepository,
                )
            }
        }
    }

    private fun preferencesFactory(): ViewModelProvider.Factory {
        val app = application as VelaLabApplication
        return viewModelFactory {
            initializer {
                VelaPreferencesViewModel(store = app.visualPreferencesStore)
            }
        }
    }
}
