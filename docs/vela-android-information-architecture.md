# VELA Android — arquitectura de información UX-2

Este documento define la arquitectura de información de UX-2 para reemplazar el dashboard único y largo por una aplicación móvil organizada por secciones. Es un contrato de presentación: reutiliza los estados y las acciones existentes, no duplica lógica y no autoriza cambios de trading.

No documenta como exitosos un build, una instalación ni una validación de runtime. Esos resultados deben registrarse únicamente después de ejecutarlos.

## 1. Mapa de navegación

En teléfono hay exactamente cinco destinos principales persistentes:

```text
VelaAppShell
├── Inicio
├── Mercado
├── Velas
├── Paper
└── Más
    ├── Riesgo
    ├── Historial y auditoría
    ├── Configuración
    └── Diagnóstico
```

`VelaSafetyBanner` queda fuera del contenido de cada destino y permanece visible en los nueve recorridos. En la barra inferior, una pantalla secundaria mantiene seleccionado `Más`; Back vuelve primero a `Más` y no salta a Paper ni a una acción de submit.

La navegación cambia solamente qué composable se presenta. Por sí sola no inicia ni detiene streams, no refresca repositorios, no crea drafts o previews y no modifica ningún estado Paper.

## 2. Responsabilidad de cada sección

| Sección | Responsabilidad | Contenido prioritario | Fuera de alcance |
| --- | --- | --- | --- |
| **Inicio** | Resumen operativo de lectura rápida. | Mercado abierto/cerrado, conexión, último refresh, símbolo y precio seleccionados, cuenta Paper resumida, riesgo resumido y última actividad local. | Diagnóstico extenso, formulario de credenciales y cualquier acción Submit. |
| **Mercado** | Observación y control explícito del pipeline de market data ya existente. | Selector de símbolo, watchlist, último quote/bar, conexión, suscripción, frescura, señales y diagnóstico de ticks colapsable. | Inicio automático del stream al entrar, endpoints nuevos o acciones de compra/venta. |
| **Velas** | Visualización local y read-only de OHLC persistido. | Símbolo de la watchlist, timeframe real `1m`, 30/50/100 barras, Canvas, frescura y detalle de vela seleccionada. | Historia remota, WebSocket propio, OHLC fabricado o enlace a una orden. |
| **Paper** | Reunir el flujo Paper existente sin alterar su orden ni sus gates. | Cuenta, estado de mercado/cuenta, preflight, draft/preview, readiness, Manual Paper submit dentro de `VelaActionZone` y referencia a su auditoría. | Ejecución automática, simplificación de blockers, cambio de `enabled`, TTL, tolerancias, endpoint o método. |
| **Más** | Índice breve hacia funciones secundarias. | Riesgo, Historial y auditoría, Configuración y Diagnóstico. | Duplicar las pantallas secundarias dentro de un único scroll. |
| **Riesgo** | Exponer el snapshot de riesgo existente con jerarquía semántica. | Equity, cash, buying power, gross market value, posiciones, market open, flags, exposiciones, datos faltantes y timestamps. | Reglas, límites o blockers nuevos. |
| **Historial y auditoría** | Consulta local, append-only y filtrable. | Pestañas/filtros Mercado, Dry-runs, Previews y Submit audit cuando este último ya esté expuesto de forma segura. | Delete, clear, mutación o datos sensibles. |
| **Configuración** | Preferencias exclusivamente visuales y estado de seguridad/conexión read-only. | Densidad, cantidad de velas, símbolo visual, diagnóstico avanzado, última sección, hora local/UTC; credenciales existentes con Save/Clear y solo el booleano configured. | Secretos en DataStore, toggles REAL/LIVE/Auto Paper/compile flag, edición de gates o endpoint. |
| **Diagnóstico** | Retirar del flujo principal el aspecto de laboratorio sin perder herramientas existentes. | FAKEPACA, demos BTC/USD y SPY, counters, tick buffer, reconnects, pipeline, DB readiness y journal. | Servicios en background, ML, red nueva o trading. |

### 2.1 Orden dentro de Paper

El orden es deliberado y no se comprime:

1. Cuenta Paper.
2. Estado de mercado y de cuenta.
3. Preflight.
4. Draft y payload preview.
5. Readiness.
6. Manual Paper submit dentro de `VelaActionZone`.
7. Queue y auditoría relacionada, o acceso explícito a su vista completa.

Todas las filas, razones de gate, edades de precio, tolerancia de future skew, drift, endpoint, método, confirmación, Arm, Disarm, Refresh gates y Submit conservan su semántica y sus condiciones `enabled` actuales. Ningún blocker se oculta.

## 3. Inventario card → estado → destino

La auditoría base corresponde a `OfflineDashboardScreen.kt`. “Adapter UI” significa una proyección pura para presentar menos campos; no puede consultar red, persistir mercado ni recalcular reglas de dominio.

| Card/componente actual | ViewModel o estado consumido | Destino UX-2 | Tipo de cambio |
| --- | --- | --- | --- |
| `StatusCard` | `OfflineDashboardViewModel` → `OfflineDashboardUiState` | Inicio; sus invariantes también alimentan el header global | Adapter UI read-only para resumen; sin lógica nueva. |
| `PipelineCard` | `OfflineDashboardUiState` | Inicio, “Mercado principal” y “Última actividad” | División visual de los campos existentes. |
| `CountersCard` | `OfflineDashboardUiState` | Inicio, última actividad; detalle en Diagnóstico | Proyección visual del mismo estado, sin contador duplicado. |
| `ErrorCard` | `OfflineDashboardUiState.lastError` | Diagnóstico, con resumen contextual en la pantalla que corresponda | Movimiento visual; no cambia captura ni texto seguro del error. |
| `ControlsCard` | callbacks de `OfflineDashboardViewModel` | Diagnóstico | Movimiento visual solamente; sigue siendo explícito y debug/lab. |
| `AlpacaCredentialsCard` | `AlpacaTestStreamViewModel` → `AlpacaTestStreamUiState` | Configuración para Save/Clear; Diagnóstico para FAKEPACA | Separación presentacional sobre el mismo VM. Los inputs no se replican ni se renderizan después de guardar. |
| `AlpacaStockStreamCard` | `AlpacaStockStreamViewModel` → `AlpacaStockStreamUiState` | Mercado | Movimiento visual. Start/Stop conservan su comportamiento y siguen requiriendo toque explícito. |
| `WatchlistCard` | `WatchlistViewModel` → `WatchlistUiState` | Mercado | Movimiento visual. Velas consume una proyección read-only de `symbols`; no crea otra watchlist. |
| `TickDiagnosticsCard` | `AlpacaStockStreamViewModel.tickBufferRef.snapshot` | Mercado → diagnóstico avanzado colapsable | Movimiento visual; no se copia el buffer. |
| `MarketHistoryCard` | `MarketHistoryViewModel` → `MarketHistoryUiState` | Historial y auditoría → Mercado | Movimiento visual. Inicio/Mercado pueden mostrar un resumen del mismo state, sin segundo refresh automático. |
| `PaperAccountCard` | `PaperAccountViewModel` → `PaperAccountUiState` | Paper | Movimiento visual. Inicio usa una proyección compacta del mismo snapshot. |
| `PaperPortfolioRiskCard` | `PaperPortfolioRiskViewModel` → `PaperPortfolioRiskUiState` | Riesgo | Movimiento visual. Inicio muestra únicamente el resumen/CTA “Ver riesgo”. |
| `PaperOrderPreflightCard` | `PaperOrderPreflightViewModel` → `PaperOrderPreflightUiState` | Paper | Movimiento visual íntegro; no se altera el dry-run. |
| `PaperExecutionReadinessCard` | `PaperOrderPreflightUiState.lastExecutionReadiness` | Paper | Movimiento visual íntegro; continúa mostrando ejecución deshabilitada y todos los motivos. |
| `PaperManualSubmitCard` | `PaperManualSubmitViewModel` → `PaperManualSubmitUiState` | Paper, dentro de `VelaActionZone` | Reparent visual solamente. La expresión de habilitación, confirmación y callbacks queda intacta. |
| `PaperOrderPayloadPreviewQueueCard` | `PaperOrderPayloadPreviewQueueViewModel` → `PaperOrderPayloadPreviewQueueUiState` | Historial y auditoría → Previews; referencia relacionada desde Paper | Movimiento visual; lista local append-only. |
| `PaperDryRunAuditCard` | `PaperOrderDryRunAuditViewModel` → `PaperOrderDryRunAuditUiState` | Historial y auditoría → Dry-runs | Movimiento visual; lista local append-only. |

No existe en la pantalla auditada una card independiente para submit audit. UX-2 solo debe mostrarla si el estado existente ya la expone sin secretos; no se crea una consulta de red ni se cambia el schema para completar una pestaña vacía.

## 4. Ownership de Activity y estado

`MainActivity` ya es dueña de los ViewModels mediante `by viewModels`: dashboard, credenciales/test stream, stock stream, watchlist, historial, cuenta Paper, portfolio risk, preflight, dry-run audit, preview queue y Manual submit. UX-2 conserva ese alcance de Activity.

Las reglas de ownership son:

- `MainActivity` crea cada VM una sola vez y entrega referencias/estados al root de Compose.
- `VelaAppShell` es dueño únicamente de navegación, scaffold y preferencias visuales; no construye clientes ni repositorios.
- Los destinos reciben estados inmutables y callbacks explícitos. Un destino no crea otro VM para obtener una segunda copia de la misma fuente.
- La selección de destino y el scroll son estado de UI. El último destino se persiste solo si la preferencia visual “recordar última sección” está habilitada.
- La selección de símbolo de Velas y el conteo 30/50/100 son estado visual; el conjunto permitido proviene de la watchlist existente.
- La sincronización ya existente de preflight/preview/readiness hacia `PaperManualSubmitViewModel.updateSource` debe vivir una sola vez en el root con alcance de Activity, no dentro de Paper. Navegar hacia Paper no debe dispararla como efecto de entrada.
- Los formularios de preflight y confirmación siguen siendo propiedad de sus ViewModels; cambiar de sección no los limpia, arma, confirma ni envía.

### 4.1 Regla de cero efectos laterales por navegación

No se permite un `LaunchedEffect(destination)` que llame `refresh()`, `startStream()`, `stopStream()`, `runDryRunPreflight()`, `buildLocalDraft()`, `buildPayloadPreview()`, `checkExecutionReadiness()`, `armSession()` o cualquier método de submit. Las lecturas con efecto ya existentes siguen detrás de sus botones explícitos o del ciclo de vida original del VM; UX-2 no añade una segunda llamada por entrar a una ruta.

La restauración de preferencias visuales puede leer DataStore. Esa lectura no toca Alpaca, Room de órdenes/auditoría ni el pipeline de mercado.

## 5. Safety header global

El header compacto se muestra por encima del contenido desplazable, en todos los destinos principales y secundarios. Conserva los pills de UX-1 y siempre expresa seis hechos:

1. `Mode READ_ONLY`.
2. `REAL locked`.
3. `Paper-only`.
4. `No LIVE endpoint`.
5. `Auto Paper disabled`.
6. `Manual submit compiled=false|true`, según el build real.

El header no infiere que un estado desconocido sea seguro: un valor no disponible debe mostrarse como desconocido/bloqueado, no ocultarse. Nunca incluye key ID, secret, headers, account id, token ni texto completo de confirmación.

La card Manual Paper permanece alejada de la barra inferior mediante insets y espacio final; ningún gesto de navegación puede coincidir con el control Submit.

## 6. Preferencias locales permitidas

DataStore Preferences se limita a:

- densidad `compacta|cómoda`;
- cantidad predeterminada de velas `30|50|100`;
- símbolo visual predeterminado, validado contra la watchlist existente;
- mostrar/ocultar diagnóstico avanzado;
- recordar última sección abierta;
- formato horario `local|UTC`;
- idioma visual únicamente si la infraestructura de strings ya lo soporta sin un refactor amplio.

No se persisten credenciales, endpoint, gates, TTL, drift, clock tolerance, arm, token, confirmación ni ningún permiso de ejecución. La pantalla de Configuración presenta REAL, LIVE, Auto Paper y compile flag como valores read-only, nunca como toggles.

## 7. Decisiones móvil frente a desktop

| Desktop VELA | Decisión Android |
| --- | --- |
| Sidebar amplia con muchos destinos simultáneos. | Barra inferior de exactamente cinco destinos; cuatro funciones secundarias viven en Más. |
| Varias columnas y KPIs permanentes. | Una columna priorizada y scroll independiente por pantalla; grids solo cuando el ancho lo permite. |
| Área útil suficiente para diagnósticos visibles. | Diagnósticos colapsados o trasladados a Diagnóstico para que Inicio no parezca un laboratorio. |
| Chart grande con interacción de mouse. | Canvas táctil, tap para detalle y desplazamiento horizontal simple; sin zoom complejo inicial. |
| Status bar y controles laterales. | Safety header compacto, insets de sistema/IME y targets táctiles de al menos 48 dp. |
| Operación con teclado y ventanas. | Formularios conservan estado entre secciones; IME no tapa inputs y Manual Submit no queda junto a navegación. |

En landscape básico puede aprovecharse más ancho dentro de una pantalla, pero no se introducen siete u ocho destinos principales ni se cambia la jerarquía semántica.

## 8. Contrato de seguridad y no trading

UX-2 no autoriza ni requiere:

- iniciar Phase 2.w;
- modificar `data/paper/**`, clientes HTTP, allowlists, gates, TTL, drift o clock tolerance;
- activar `MANUAL_PAPER_SUBMIT_COMPILED` ni tocar `local.properties`;
- crear un endpoint, WebSocket, foreground service o trabajo de trading en background;
- desbloquear REAL, habilitar LIVE o Auto Paper;
- agregar retry, cancel, replace o close position;
- generar arm, token o confirmación por navegar;
- ejecutar un POST.

La reubicación de una card preserva su callback y su condición de habilitación. Una proyección UI solo formatea o reduce datos ya presentes: no toma decisiones de riesgo, precio ni ejecución.
