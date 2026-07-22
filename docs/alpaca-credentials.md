# Configuración de credenciales de Alpaca para el lab Android

Hay **dos** formas de proveer credenciales del stream de prueba de Alpaca
Market Data al lab Android. La forma definitiva, y la que la app
muestra desde adentro, es **(A) el flujo en-app de Phase 2.c.1**.
**(B) local.properties** quedó únicamente como **atajo de desarrollador
para builds debug**.

**Ningún valor de credencial vive en este repositorio.** Este documento
no contiene claves, secretos, ni valores de ejemplo que se parezcan a
tokens reales de Alpaca.

## Alcance (lo mismo para ambos caminos)

Las credenciales se usan **únicamente** para autenticar contra el
WebSocket de sólo lectura del stream de prueba de Alpaca Market Data en:

```
wss://stream.data.alpaca.markets/v2/test
```

**Nunca** se usan para:

- enviar, cancelar o consultar órdenes
- consultar o mutar el estado de la cuenta
- acceder a las APIs de trading live o paper
- acceder a cualquier URL que no sea el stream de prueba

La lista blanca de endpoints vive en
[AlpacaStreamEndpoint.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaStreamEndpoint.kt)
y el cliente del stream de prueba la aplica en tiempo de construcción.

## (A) Flujo en-app — Phase 2.c.1 (recomendado)

Es el flujo de UX equivalente a la pantalla de credenciales de la app de
Windows VELA: el usuario las ingresa **desde adentro** del lab Android.

### Cómo se ven en disco

- Los valores se guardan en `EncryptedSharedPreferences` con clave maestra
  generada por `MasterKey.Builder` y sellada por el **Android Keystore**.
  En dispositivos con Strongbox, la clave maestra no sale del hardware
  seguro.
- El archivo de preferencias se llama `vela_alpaca_credentials` y está en
  almacenamiento privado de la app (`/data/data/com.vela.android.lab/...`).
  Otra app no puede leerlo, e incluso si tuviera acceso al archivo el
  contenido está cifrado con AES-256-GCM por valor y AES-256-SIV por clave.
- El manifiesto del lab tiene `android:allowBackup="false"` desde Phase 0,
  así que ningún agente de backup mete el blob cifrado en la nube.

### Cómo las ingresa el usuario

1. Abrí la app (debug). Es la pantalla principal.
2. Bajá hasta la tarjeta **"Alpaca Paper Credentials"**.
3. Pegá tu **Key ID** y **Secret** de Alpaca **Paper** en sus campos. El
   campo Secret está enmascarado con `PasswordVisualTransformation`.
4. Tocá **Save credentials**.
5. La app muestra `Credentials configured: true`. Los campos de texto se
   limpian solos para que el secret no quede en pantalla.

### Cómo correr el smoke test

1. Con credenciales guardadas, tocá **Test Alpaca Market Data**.
2. El estado **Connection** pasa por `CONNECTING` → `CONNECTED` y
   `Bars received` empieza a subir cuando llegan barras de **FAKEPACA**.
3. Tocá **Stop Alpaca test stream** para cerrar el WebSocket.

### Cómo las quita el usuario

- Tocá **Clear credentials** dentro de la tarjeta.
- La app muestra `Credentials configured: false`.

### Lo que el flujo en-app garantiza

- Las credenciales nunca se renderizan después de guardar — sólo se ve el
  booleano `Credentials configured`.
- El secret nunca se loguea.
- El secret nunca queda en el `uiState` después del save (el ViewModel
  limpia ambos campos en el mismo update que persiste a disco).
- `toString()` de `AlpacaCredentials` redacta el secret entero y trunca el
  key id a su prefijo.

## (B) local.properties — sólo atajo de desarrollador (Phase 2.c)

Antes de Phase 2.c.1 este era el único camino. Sigue funcionando para
desarrollo headless (correr el smoke test sin tener que tocar la UI), pero
**no es la UX final**. La app prefiere siempre las credenciales del
almacén seguro; sólo si éste está vacío usa `BuildConfig`.

### Dónde viven los valores (path B)

`app/build.gradle.kts` lee dos campos opcionales de `local.properties` en
tiempo de compilación y los expone como `BuildConfig.ALPACA_TEST_KEY_ID` /
`BuildConfig.ALPACA_TEST_SECRET`:

| Tipo de build | Valor en BuildConfig |
|---|---|
| **debug** | lo que el desarrollador haya escrito en `local.properties` (por defecto `""`) |
| **release** | siempre `""`, sin importar el contenido de `local.properties` |

`local.properties` está en `.gitignore` por defecto en proyectos de Android
Studio. **No lo commitees.** Si tenés dudas, corré
`git check-ignore -v local.properties` desde adentro de `android/` antes
de commitear nada.

### Cómo agregar credenciales en `local.properties`

1. Abrí el dashboard de Alpaca y generá un par key id + secret de **Paper
   trading**. Usá esa clave solamente para el stream de prueba.
2. Abrí `G:\vela-android\android\local.properties` y agregá dos líneas:

   ```properties
   # Phase 2.c: credenciales del stream de prueba de Alpaca Market Data.
   # NO COMMITEAR. local.properties está en gitignore.
   ALPACA_TEST_KEY_ID=<tu paper key id acá>
   ALPACA_TEST_SECRET=<tu paper secret acá>
   ```

3. Recompilá el APK debug. Si Gradle reporta "up-to-date" pero los valores
   son nuevos, forzá `:app:assembleDebug --rerun-tasks` (ver nota técnica
   abajo).

### Orden de búsqueda en runtime

El proveedor compuesto en `VelaLabApplication.alpacaCredentialsProvider`
es:

1. `SecureAlpacaCredentialsProvider` (almacén cifrado) — flujo (A)
2. `BuildConfigAlpacaCredentialsProvider.fromBuildConfig()` — flujo (B)

El primero con valor no nulo gana. Esto significa:

- Si guardaste credenciales en la app, el lab usa esas. `local.properties`
  queda ignorada.
- Si limpiás las credenciales en la app, el lab vuelve a usar las de
  `local.properties` si están.
- En release, `BuildConfig` siempre está vacío, así que la única forma
  de tener credenciales en release sería el flujo (A).

### Sobre el "up-to-date" de Gradle

`buildConfigField` se evalúa en tiempo de configuración. Si modificás
`local.properties` Gradle no necesariamente refresca el fingerprint de la
tarea `generateDebugBuildConfig`, y `:app:assembleDebug` puede reportar
"all up-to-date" produciendo un APK con valores viejos. Solución:

```
.\gradlew.bat :app:assembleDebug --rerun-tasks --console=plain --no-daemon `
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'
```

## ¿Y si commiteás `local.properties` por error?

Rotá las credenciales inmediatamente en el dashboard de Alpaca, después
hacé `git rm --cached local.properties` y enmendá el commit ofensor.
Force-pusheá sólo si el commit todavía no se publicó. Si el commit ya es
público, tratá tanto al key id como al secret como comprometidos y rotalos.

La compilación release nunca toma los valores de `local.properties` ni
del almacén cifrado entre dispositivos (cada install tiene su propio
Keystore), así que un APK enviado a cualquier otra persona no puede
llevar las credenciales aunque `local.properties` brevemente las haya
contenido.

## Verificación de las garantías de seguridad

- `grep -i "ALPACA_TEST_KEY_ID\|ALPACA_TEST_SECRET" -r G:\vela-android\android\app\src`
  debería mostrar sólo los nombres de los campos de `BuildConfig`, nunca
  valores.
- `grep -rE "PK[A-Z0-9]{16,}" G:\vela-android\android\app\src` no debería
  mostrar nada — esa es la forma del prefijo de las paper keys de Alpaca.
- Los tests de contrato por reflexión de Phase 2.b/2.c
  (`AlpacaTestStreamClientContractTest`, `MarketDataClientContractTest`)
  fallan ruidosamente si alguna clase de `data.market.source.*` adquiere
  un método con forma de trading.
- `:app:testDebugUnitTest` cubre el save → clear → load del store,
  el orden de la cadena compuesta, y que el `uiState` nunca conserve el
  secret después de guardar (búsqueda literal del valor en `toString()`).
