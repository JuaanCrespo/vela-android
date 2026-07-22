# VELA Android — experiencia de velas read-only UX-2

La pantalla **Velas** presenta barras OHLC de un minuto que ya existen localmente. No descarga historia, no abre un stream, no fabrica OHLC y no conecta el gráfico con trading.

Este documento describe el contrato de datos e interacción. No afirma que un build, APK o runtime visual haya sido validado.

## 1. Fuente de datos autorizada

La fuente de la pantalla es `MarketDataRepository.recentBars(symbol, limit)`, que lee `MarketBarDao.recent`, convierte cada `MarketBar1mEntity` a `OneMinuteBar` y devuelve la lista en orden cronológico, de la barra más antigua a la más nueva.

El botón **Refresh local** repite únicamente esa lectura de Room. No llama a Alpaca, no inicia `AlpacaStockStreamViewModel` y no escribe barras. Las barras nuevas solo aparecen si el pipeline ya existente las persistió por su propio flujo autorizado.

### 1.1 Modelo de dominio: `OneMinuteBar`

| Campo | Tipo | Uso en Velas |
| --- | --- | --- |
| `symbol` | `String` | Símbolo normalizado y clave de selección. |
| `bucketStart` | `Instant` | Inicio UTC del bucket de un minuto y posición en el eje temporal. |
| `open` | `Double` | Apertura del cuerpo. |
| `high` | `Double` | Extremo superior de la mecha/rango. |
| `low` | `Double` | Extremo inferior de la mecha/rango. |
| `close` | `Double` | Cierre del cuerpo, dirección y línea del último cierre. |
| `updateCount` | `Int` | Diagnóstico opcional de cuántas actualizaciones formaron la barra. |
| `syntheticVolume` | `Double` | Volumen disponible, con la salvedad indicada abajo. |
| `lastUpdateTime` | `Instant?` | Timestamp preferido para calcular frescura cuando existe. |

El modelo sí contiene OHLC completo y no necesita reconstruir una vela desde un precio aislado.

### 1.2 Persistencia Room: `MarketBar1mEntity`

Room conserva:

- `id`;
- `symbol`;
- `bucketStartEpochMillis`;
- `open`, `high`, `low`, `close`;
- `updateCount`;
- `syntheticVolume`;
- `lastUpdateTimeEpochMillis` nullable.

La combinación `(symbol, bucketStartEpochMillis)` es única. El mapper `toDomain()` restaura `bucketStart` y `lastUpdateTime` como `Instant`; no cambia los valores OHLC.

### 1.3 Caveat de volumen

`syntheticVolume` no debe rotularse como volumen bursátil real sin aclaración. El agregador usa el volumen recibido cuando la actualización lo contiene; si no lo contiene, usa/incrementa un valor sintético basado en el número de actualizaciones. La UI muestra **“Volumen (sintético/recibido)”** y no deriva liquidez, riesgo o una señal de trading de ese campo.

### 1.4 La fuente del feed no está persistida

Ni `OneMinuteBar` ni `MarketBar1mEntity` guardan un campo `source`, `feed` o `endpoint`. Por lo tanto, una barra leída de Room no puede presentarse como “IEX”, “FAKEPACA” o cualquier otra fuente solo por el estado actual de la conexión.

La etiqueta segura es:

```text
Room local · source not persisted
```

El estado `Connected` de un stream puede mostrarse como telemetría actual separada, pero no prueba el origen de las barras históricas visibles.

## 2. Timeframe y cantidad

- Timeframe real: **`1m` solamente**.
- No se muestran opciones `5m`, `15m`, `1h` ni otra agregación inexistente.
- Cantidades seleccionables: **30, 50 y 100**.
- La preferencia visual define el valor inicial; una selección de sesión puede cambiarlo sin modificar Room.
- `recentBars(symbol, count)` limita la consulta. Si hay menos filas, se muestran las disponibles; nunca se rellenan huecos con velas artificiales.
- El símbolo se elige únicamente desde la watchlist existente. Si el símbolo preferido ya no pertenece a ella, se usa el primer símbolo disponible o el estado No data.

## 3. Estado read-only de la pantalla

El adapter/UI ViewModel de Velas puede mantener exclusivamente:

- símbolo seleccionado;
- count seleccionado `30|50|100`;
- lista cronológica de `OneMinuteBar`;
- vela seleccionada por `(symbol, bucketStart)`;
- `isLoading`, error seguro y timestamp del último refresh local;
- market open/closed y conexión como snapshots read-only ya existentes;
- edad y clasificación de frescura calculadas desde timestamps locales.

No contiene cliente HTTP, credenciales, order intent, draft, preview, readiness, arm, token ni callback de Submit.

## 4. Validación de barras

Aunque los campos OHLC no son nullable, el adapter valida antes de dibujar:

- valores finitos y positivos;
- `high >= max(open, close)`;
- `low <= min(open, close)`;
- `high >= low`;
- símbolo no vacío y timestamp válido.

Una fila inválida no se “corrige” usando el close ni se convierte en vela plana. Se excluye del Canvas y se informa un error read-only/“Datos OHLC insuficientes” con un conteo seguro. Si ninguna fila válida queda, se muestra el estado vacío correspondiente.

## 5. Frescura y tiempo

El timestamp efectivo de la última barra es:

```text
lastUpdateTime ?: bucketStart
```

La edad se calcula contra el reloj actual y se muestra de forma explícita. La política de stale debe estar centralizada y probada para el timeframe `1m`; la referencia UX es **dos buckets (120 segundos) mientras `marketOpen=true`**. No se recorta una edad negativa para aparentar frescura: un timestamp futuro se presenta como anomalía temporal/error read-only.

Cuando el mercado está cerrado, la pantalla muestra `Market closed` y conserva el timestamp/edad del último dato; no promete que esa barra sea “live”. Cuando `marketOpen` es desconocido, se muestra `Market status unknown` en vez de inferir abierto.

La zona horaria solo cambia el formato de las etiquetas (`local` o `UTC`); no altera los `Instant`, el orden ni el cálculo de edad.

## 6. Estados visuales

Los estados de carga y contenido tienen precedencia; los badges de mercado/conexión pueden coexistir:

| Estado | Criterio | Presentación |
| --- | --- | --- |
| **Loading** | Lectura local en curso y todavía no hay snapshot utilizable. | Skeleton/indicador discreto; controles que causarían otra lectura deshabilitados. |
| **No data** | Room devuelve cero barras válidas para símbolo/count. | `VelaEmptyState`, símbolo, `1m` y acción Refresh local. |
| **Datos OHLC insuficientes** | Hay filas, pero ninguna o parte no cumple el contrato OHLC. | Mensaje seguro y conteo; nunca velas fabricadas. |
| **Stale data** | Mercado abierto y edad mayor que la política de frescura. | `VelaFreshnessBadge` warning, timestamp y edad visibles. |
| **Market closed** | Snapshot existente indica `marketOpen=false`. | Badge neutral/warning; el gráfico histórico sigue visible. |
| **Connected** | Telemetría actual del stream existente indica conexión/suscripción. | Badge independiente; no se usa como procedencia de las barras Room. |
| **Error read-only** | Falló la lectura/mapeo local o existe anomalía temporal. | Mensaje saneado y Refresh local; no fallback de red. |

Un error posterior a un snapshot no borra necesariamente el último gráfico válido: puede conservarlo con un banner de error y su timestamp, sin marcarlo como fresco.

## 7. Gráfico con Compose Canvas

`VelaCandlestickChart` es un componente presentacional sin dependencia de gráficos pesada.

### 7.1 Dibujo

- Fondo dark navy y grilla de bajo contraste.
- Área de plot separada de márgenes para eje de precio y etiquetas temporales.
- Escala Y basada en el mínimo `low` y máximo `high` de las barras visibles, con padding visual y manejo explícito del rango cero.
- Mecha desde `low` hasta `high`.
- Cuerpo entre `open` y `close`, con una altura mínima visual para doji sin falsear sus valores.
- Alcista: mint VELA; bajista: rojo/ámbar sobrio; doji: tono neutral y etiqueta textual en detalle.
- Línea del **último cierre persistido**, rotulada como tal. No se llama “último quote” si no proviene de un quote.
- Eje temporal simplificado con pocas etiquetas legibles; eje de precio con valores formateados.
- Conteos altos usan ancho mínimo por vela y desplazamiento horizontal simple. No se comprimen 100 velas hasta volverlas imposibles de tocar.

### 7.2 Tap y selección

Un tap resuelve la vela más cercana dentro del área de plot y guarda su clave estable `(symbol, bucketStart)`. La selección se resalta sin cambiar datos ni disparar efectos. El panel de detalle muestra:

- timestamp en el formato horario elegido;
- open, high, low y close;
- volumen con caveat;
- dirección `Bullish`, `Bearish` o `Doji`;
- rango `high - low`;
- `updateCount`, si aporta diagnóstico.

La selección se limpia si cambia el símbolo o si el refresh ya no contiene esa clave. No hay long-press de orden, botones Buy/Sell ni navegación automática a Paper.

### 7.3 Accesibilidad

- La dirección no depende solamente del color: el detalle la nombra y la selección tiene indicador adicional.
- El Canvas expone una descripción resumida del símbolo, count, timeframe, rango temporal y última barra.
- Los controles tienen target táctil mínimo de 48 dp y soportan font scaling.
- Las etiquetas evitan solapamiento en Pixel 5/VELA_Lite portrait y en landscape básico.

## 8. Controles permitidos

La pantalla ofrece solamente:

- selector de símbolo desde la watchlist;
- selector 30/50/100;
- indicador fijo `1m`;
- `Refresh local`;
- tap para seleccionar una vela;
- desplazamiento horizontal simple cuando haga falta.

Refresh no inicia un stream y entrar a Velas tampoco. La pantalla observa el stream health existente solo si ya está disponible.

## 9. Límites conocidos

- Solo hay datos que el pipeline existente haya persistido localmente.
- No se solicita backfill ni historia remota; puede haber menos de 30 barras o huecos temporales.
- Room no conserva la procedencia del feed.
- `syntheticVolume` puede representar actualizaciones, no volumen de mercado.
- La línea final representa el último `close` persistido, no necesariamente bid, ask o quote actual.
- No hay zoom complejo, indicadores técnicos, overlays de señales, anotaciones de órdenes ni edición.
- No se cambia el schema Room ni se agrega una tabla de velas.
- No se inventa OHLC, no se interpola un bucket faltante y no se convierte un tick único en vela.

## 10. Frontera de seguridad

La ruta Velas y sus componentes no pueden:

- importar o invocar clientes HTTP/Alpaca;
- iniciar o detener WebSockets por navegación;
- escribir en Room;
- modificar la watchlist como efecto del selector;
- construir preflight, draft, preview o readiness;
- armar sesión, generar token o pedir confirmación;
- habilitar REAL, LIVE, Auto Paper o `MANUAL_PAPER_SUBMIT_COMPILED`;
- exponer cancel, replace o close position;
- ejecutar POST.

No existe enlace gráfico → orden. Para llegar a Paper, el operador usa la navegación normal; el símbolo seleccionado en Velas no precompleta ni altera un formulario de orden como efecto implícito.
