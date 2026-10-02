# TCG Scanner Offline

App Android (Kotlin + Jetpack Compose) para coleccionistas de cartas TCG que **funciona sin internet**: un catálogo local en SQLite (Room) se actualiza una vez al día y permite escanear, consultar precios y negociar en torneos o convenciones sin señal.

Sin servidores propios, sin cuentas, sin anuncios, sin suscripciones. Exportar a CSV es gratis y sin límites.

## Qué resuelve

| Dolor del sector | Cómo lo resuelve la app |
|---|---|
| Sin señal en torneos | Catálogo + precios en Room; OCR (ML Kit) y búsqueda 100 % locales |
| Variantes mal reconocidas | Tras detectar la carta, un *bottom sheet* pregunta la versión (Normal / Holo / Reverse / 1.ª Edición…) y el estado o el *slab* |
| Paywalls | CSV, respaldo JSON, escaneo y mazos sin límites ni pagos |
| Raw vs Graded | Valores separados; PSA/BGS/CGC/SGC con precio publicado, manual o estimado (marcado con ≈) |
| Master Sets | Barras de progreso por set; faltantes en escala de grises; modo «master» (todas las variantes) |
| Bases de datos asiáticas | Pokémon Japón con OCR japonés (ML Kit) y fuentes pokemontcg.io / TCGdex |

## Pantallas

1. **Hub de juegos** – cuadrícula con cada TCG activado; al elegir uno toda la app se filtra a ese juego.
2. **Portafolio** – gráfica de líneas dibujada localmente (7D/30D/90D/1A/Todo, con arrastre para ver valores), Raw vs Graded, última sincronización, accesos a *Mis mazos* y *Más valiosas*.
3. **Escáner contextual offline** – CameraX + ML Kit (latino / japonés) buscando solo en el juego activo; búsqueda manual; identificación por ilustración (hash perceptual y, opcionalmente, TensorFlow Lite).
4. **Gestor de mazos** – agrupado por tipo, arrastrar y soltar (resultados → mazo/sideboard, y entre zonas), botones +/− accesibles, faltantes en rojo como lista de deseos con costo estimado.
5. **Catálogo y Master Sets** – sets con porcentaje, detalle con cartas faltantes en gris, alta de cartas propias.
6. **Trade Binder** – carrusel con el valor total disponible para intercambio.
7. **Ajustes** – sincronizar precios, CSV, respaldo/restauración JSON, índice de ilustraciones, importar catálogo, borrar datos.
8. **Intercambio P2P** – Google Nearby Connections (Bluetooth / Wi-Fi Direct, sin internet): emparejamiento con código, comparación de binders y veredicto justo/injusto con **tus** precios locales.

## Arquitectura

```
app/src/main/java/com/tcgscanner/offline
├── core/        juegos, variantes, utilidades de texto
├── data/
│   ├── db/      Room: cartas, precios, graded, colección, mazos, snapshots, firmas
│   ├── remote/  Http (OkHttp) + fuentes de catálogo con fallback
│   ├── repo/    catálogo/sincronización, colección, portafolio, mazos, CSV, respaldo
│   └── prefs/   DataStore (ajustes y URLs de catálogo)
├── scanner/     parser OCR, matcher, analizador CameraX, firmas visuales, TFLite y ModelRepository
├── trade/       Nearby Connections + protocolo de intercambio
├── work/        WorkManager: sincronización de catálogos y descarga del modelo
└── ui/          Compose (Material 3), navegación, pantallas
```

* **Sin framework de DI**: `AppContainer` manual.
* **Datos de usuario a salvo**: no hay claves foráneas desde la colección hacia el catálogo, así que refrescar el catálogo nunca borra tu colección.
* **Sync**: cada juego tiene una lista ordenada de fuentes (principal → respaldos). Los datos se guardan por lotes mientras se descargan (Scryfall se procesa en *streaming*).
* **Snapshots del portafolio**: uno por hora como máximo; el último valor de cada hora gana. La gráfica añade el valor «en vivo».

## Fuentes de datos (sin credenciales)

La app **no pide claves de API ni usa servidores propios**. Cada juego tiene una URL de base de datos por defecto (`data/remote/CatalogUrls.kt`) y una o dos fuentes públicas de respaldo:

| Juego | URL por defecto | Respaldo |
|---|---|---|
| Pokémon | `api.pokemontcg.io/v2/cards` (paginado, 250 por página) | TCGdex |
| Magic | `api.scryfall.com/bulk-data/default-cards` (descriptor → descarga masiva en *streaming*) | MTGJSON |
| Yu-Gi-Oh! | `db.ygoprodeck.com/api/v7/cardinfo.php` | – |
| Lorcana | `api.lorcana-api.com/cards/all` | Lorcast |
| One Piece | `raw.githubusercontent.com/optcg-community/optcg-data/main/cards.json` | OPTCG API |
| Digimon | `digimoncard.io/api-public/search.php?series=Digimon Card Game` | – |
| Pokémon Japón | `api.pokemontcg.io/v2/cards?q=language:japanese` | TCGdex (ja) |
| Fusion World | `raw.githubusercontent.com/limitless-community/dbs-fw-data/main/cards.json` | – |
| Gundam | `raw.githubusercontent.com/bandai-tcg-community/gundam-db/main/cards.json` | – |
| Riftbound | `raw.githubusercontent.com/riftbound-tts/mod-data/main/database.json` | – |

**`UrlCatalogSource` detecta el formato** leyendo los primeros 16 KB (Pokémon TCG API, Scryfall, YGOPRODeck, lorcana-api, DigimonCard.io, el formato propio de `docs/CATALOG_FORMAT.md`) y, para cualquier otro JSON (volcados de la comunidad, datos de mods de Tabletop Simulator), usa un extractor genérico que busca objetos con forma de carta (nombre + número/imagen), con alias de campos (`Nickname`, `card_number`, `FaceURL`…).

**Override manual:** en *Ajustes → Bases de datos de cartas* cada juego activo tiene un campo «URL de la base de datos». Al guardar (un enlace `github.com/.../blob/...` se convierte solo a `raw.githubusercontent.com`) se encola un sync de WorkManager que actualiza Room desde ese enlace. Vacío = vuelve al valor por defecto. Solo se aceptan enlaces `https`.

**Sincronización (WorkManager, `PriceSyncWorker`):** primera descarga al activar un juego (y en cada arranque si algún juego activo sigue sin catálogo), refresco diario periódico, «Sincronizar precios» manual y tras cambiar una URL. Las tareas se encadenan (`APPEND_OR_REPLACE`) y un `Mutex` garantiza un solo sync a la vez. Si la fuente principal falla o devuelve 0 cartas se prueban los respaldos; el catálogo anterior se conserva. Cambiar de fuente puede dejar cartas antiguas junto a las nuevas (la colección nunca se borra).

> Varias de estas URLs comunitarias (One Piece, Fusion World, Gundam, Riftbound) **no pude verificarlas**: pueden no existir o tener otro esquema. Por eso existen el override, el extractor genérico y la importación de archivos locales. `pokemontcg.io` es un catálogo en inglés, así que es probable que la consulta `language:japanese` no devuelva nada y entre el respaldo TCGdex.

## Escáner

1. **ML Kit Text Recognition** (modelo incluido en el APK, offline): lee nombre y número impreso (`OP01-120`, `025/198`, `LOB-EN005`…). `CardTextParser` extrae candidatos y `ScanMatcher` los cruza en Room solo para el juego activo (número exacto + similitud de nombre con tolerancia a errores de OCR). Hacen falta 2 lecturas consistentes antes de fijar.
2. **Resolución de variantes**: *bottom sheet* con versión, estado/slab, cantidad y precio manual opcional, más «otras ediciones de esta carta».
3. **Cartas de arte completo y motor visual**: el escáner abre con OCR inmediatamente, sin pantallas de carga. Si `filesDir/card_embedder.tflite` no existe, una tarjeta translúcida sobre la cámara ofrece descargarlo (~15 MB) con un `OneTimeWorkRequest` (`ModelDownloadWorker`: reanudable, escribe a `.part` y valida tamaño/SHA-256 antes de renombrar). `ModelRepository.state` (`StateFlow`) se deriva del `WorkInfo` y del archivo: `Missing → Downloading(progreso) → Ready`. Al llegar a `Ready` se carga el intérprete, la tarjeta desaparece y el botón «Identificar por ilustración» se habilita sin reiniciar nada. Las firmas de ilustraciones se generan desde Ajustes (opcional). Ver `docs/SCANNER_MODEL.md`.

## Compilar

Requisitos: Android Studio Narwhal+ (o JDK 17 + Android SDK 36).

```bash
./gradlew testDebugUnitTest      # pruebas unitarias
./gradlew assembleDebug          # APK de depuración
./gradlew bundleRelease          # AAB para Play Store
```

Firma de *release*: crea `keystore.properties` en la raíz (nunca lo subas al repositorio):

```
storeFile=/ruta/mi-upload-key.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## Play Store

Ver `docs/PLAY_STORE_CHECKLIST.md` y `docs/PRIVACY_POLICY.md`. Resumen: `targetSdk 36`, AAB, sin servicios en primer plano, permiso de cámara con justificación en pantalla, permisos *nearby* solo al iniciar un intercambio, tráfico solo HTTPS, localización en inglés y español, política de privacidad dentro de la app.

## Estado de verificación

El proyecto se escribió en un entorno sin Android SDK ni acceso a Google Maven, por lo que **el APK completo no pudo compilarse allí**. Lo que sí se verificó con la JVM:

* compilación de `core`, `data/remote` (todas las fuentes), entidades, valoración, parser OCR y modelos de intercambio;
* 31 pruebas unitarias en `app/src/test` (texto, parser OCR, valoración, CSV, veredicto de intercambio, adaptadores y detección de formato, estados del modelo);
* pruebas adicionales de `UrlCatalogSource` y las fuentes de respaldo contra respuestas simuladas (paginación, descriptor de Scryfall, override con enlace *blob*, enlace caído).

Antes de publicar: compila en Android Studio, corrige cualquier error menor de API, y prueba en dispositivo real el escáner, el arrastrar y soltar, Nearby (dos teléfonos con Google Play Services) y las fuentes con red real.

## Aviso legal

Herramienta independiente y no oficial. Los nombres de juegos e imágenes pertenecen a sus dueños y se usan solo para identificar cartas. Los emblemas de la app son generados (sin logos de terceros). Los precios son informativos, no asesoría financiera.
