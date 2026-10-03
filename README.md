# TCG Scanner Offline

App Android (Kotlin + Jetpack Compose) para coleccionistas de cartas TCG que **funciona sin internet**: un catálogo local en SQLite (Room) se actualiza una vez al día y permite escanear, consultar precios y negociar en torneos o convenciones sin señal.

Sin servidores propios, sin cuentas, sin suscripciones. Muestra un banner de AdMob (con formulario de consentimiento UMP). Exportar a CSV es gratis y sin límites.

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

La app **no pide claves de API ni usa servidores propios**. URLs por defecto (`data/remote/CatalogUrls.kt`):

| Juego | URL por defecto | Respaldo |
|---|---|---|
| Pokémon | `api.pokemontcg.io/v2/cards` (paginado, 250 por página) | TCGdex |
| Pokémon Japón | `api.pokemontcg.io/v2/cards?q=language:japanese` | TCGdex (ja) |
| Magic | `api.scryfall.com/bulk-data/default-cards`: `ScryfallSource` dedicada, sin detección de formato: GET 1 → `download_uri`, GET 2 nuevo → `JsonReader.beginArray()` en streaming | MTGJSON |
| Yu-Gi-Oh! | `db.ygoprodeck.com/api/v7/cardinfo.php` | – |
| Digimon | `digimoncard.io/api-public/search.php?series=Digimon Card Game` | – |
| Lorcana | `api.lorcana-api.com/cards/all` | Lorcast |
| One Piece | `raw.githubusercontent.com/Coko7/vegapull-records/main/data/english/packs.json` → un `cards_<id>.json` por pack | OPTCG API |
| Fusion World | `raw.githubusercontent.com/dragogodev/cgs/master/Dragon%20Ball%20Super%20Fusion%20World/cgs.json` (descriptor de **Card Game Simulator**: `allCardsUrl` / `allSetsUrl`) | — |

**Gundam y Riftbound se eliminaron** de la app (sin fuente pública estable). Si una URL por defecto falla, el campo de Ajustes permite pegar otra; un juego sin URL ni catálogo muestra el aviso *«Importa un catálogo JSON o pega la URL comunitaria…»*.

**Magic:** la descarga sigue el descriptor de `bulk-data/default-cards` hasta su `download_uri` (también acepta la lista `/bulk-data`). No se usa `AllIdentifiers.json` de MTGJSON como fuente principal: pesa cientos de MB, no trae nombres de set ni imágenes y es inviable en un teléfono; MTGJSON (por sets) queda como respaldo.

**Detección de formato** (`UrlCatalogSource`, primeros 16 KB): Pokémon TCG API, Scryfall (descriptor y array), YGOPRODeck, lorcana-api, DigimonCard.io, **vegapull-records** (índice de packs y `cards_*.json`), **Card Game Simulator** (descriptor del juego) y, para cualquier otro JSON (volcados comunitarios, datos de mods de Tabletop Simulator, el formato de `docs/CATALOG_FORMAT.md`), un extractor genérico con alias de campos.

**Override manual:** *Ajustes → Bases de datos de cartas*: URL por juego (los enlaces `github.com/.../blob/...` se convierten solos a `raw.githubusercontent.com`; solo https). Vacío = valor por defecto.

> **No pude verificar ninguna de estas URLs ni los esquemas de vegapull-records / CGS** (el entorno de desarrollo no tiene acceso a esos hosts); están implementados según lo que sé de sus formatos. `pokemontcg.io` es un catálogo en inglés, así que es probable que `language:japanese` no devuelva nada y entre el respaldo TCGdex.

## Duplicados, cambio de fuente y re-vinculación

* **Llave estable.** `card.id` ya no es un id de red sino `game:set:número[:printTag]` (`CardKeys`), p. ej. `pokemon:base1:4`. Volver a descargar los mismos datos hace *upsert* en el sitio, no duplicados. `printTag` distingue impresiones con el mismo set+número (rarezas de Yu-Gi-Oh!, artes alternos de One Piece `_p1`); si una fuente repite una llave sin etiqueta se añade un sufijo determinista (`dup2`, `dup3`…) para que la segunda carta no pise a la primera.
* **Purga transaccional.** Si la URL efectiva cambia respecto a la que originó los datos guardados (`sync_state.sourceUrl`), o el worker recibe `purge` tras guardar un override, `CatalogRepository` borra precios, precios graduados, firmas y cartas **de ese juego** en la misma transacción que inserta el primer lote nuevo. Si la URL nueva falla, no se borra nada.
* **Colección intacta.** Nunca se tocan colección, mazos ni Trade Binder (y no hay claves foráneas hacia el catálogo). Las cartas personalizadas tampoco.
* **Re-vinculación heurística.** Cada fila de colección/mazo guarda una *foto* de identidad (nombre, set, número, etiqueta). Tras cada sync (y al importar o restaurar) `CardRelinker` busca filas cuyo catálogo ya no existe y las conecta por número exacto + similitud de nombre/set (`RelinkMatcher`); si hay empate no adivina y la fila conserva su foto (se sigue mostrando por nombre).
* **Migración Room 1→2** (`AppDatabase.MIGRATION_1_2`): añade las columnas, rellena las fotos y vacía el catálogo descargable para que se regenere con llaves estables.

## Escáner: pipeline híbrido en el dispositivo

```
cámara ─► 1. YOLO11n (TFLite)        caja de la carta → refinado de esquinas → recorte con corrección de perspectiva
        ─► 2. PaddleOCR-Mobile (ONNX) texto de la carta limpia → Regex del juego (p. ej. OP01-120) → Room (índices)
        ─► 3. EfficientNet-Lite0      solo si el usuario pulsa «Identificar por ilustración» → coseno sobre vectores en RAM
        ─► 4. Bottom sheet            la persona elige la variante (Normal / Holo / Reverse / 1.ª Edición…) y el estado o slab
```

* **Degradación elegante.** Sin descargar nada la cámara ya lee: ML Kit (incluido en el APK) sobre el marco guía. Un aviso translúcido ofrece el *motor de escaneo* (YOLO11n + PaddleOCR, ~21 MB); después, el *motor visual* (EfficientNet-Lite0, ~13 MB). Cada motor se descarga con WorkManager (`ModelDownloadWorker`, reanudable, validado) y su `StateFlow` (`ModelRepository`) hace que el aviso desaparezca y el motor se cargue sin reiniciar (ver `docs/SCANNER_MODEL.md`).
* **Fase 1** (`scanner/pipeline`): `YoloCardDetector` (letterbox + `YoloDecoder` + NMS) → `QuadRefiner` (Sobel + Otsu + casco convexo, sin OpenCV) → `PerspectiveCropper` (`Matrix.setPolyToPoly`) a 512×704. Si la carta está de lado o al revés, el siguiente fotograma prueba 180°. El contorno detectado se dibuja sobre la vista previa.
* **Fase 2** (`scanner/paddle`): detector DB + reconocedor CTC con ONNX Runtime (`PaddleOcrEngine`, post-proceso en Kotlin puro: `DbPostProcessor`, `CtcDecoder`). `CardPatterns` define el Regex por juego (One Piece `[A-Z]{2}\d{2}-\d{3}` y variantes, Digimon, Yu-Gi-Oh!, Gundam, Fusion World, Riftbound, fracciones tipo `025/198`) y corrige confusiones O/0 e I/l/1 del OCR.
* **Rendimiento de la búsqueda.** Todas las consultas de la cámara empiezan por `gameId`: `(gameId, setKey, numberKey)` para set+número, `(gameId, numberKey)`, `(gameId, nameKey)` para nombre exacto y *range scan* por prefijo; solo si no hay ningún candidato se hace un `LIKE '%…%'`. Los índices están declarados en la entidad (`CardEntity`, alias `CatalogCard`; columnas `game_id`=`gameId`, `set_number`=`setKey`, `card_number`=`numberKey`, `name`=`nameKey`) y hay migración Room 2→3.
* **Fase 3 sin SQLite.** Al iniciar, `ScannerViewModel` carga en RAM (`viewModelScope`) los embeddings del juego activo en un `VectorIndex` (un único `FloatArray` contiguo, vectores normalizados); cada consulta es un recorrido con productos punto (coseno) y un top‑k, sin tocar la base de datos. Si no hay número legible tras varios fotogramas, el botón se promueve a acción principal.
* **Modelos** (sin entrenar nada; `tools/model/`): `export_yolo_card_detector.py`, `prepare_ocr_pack.py`, `export_efficientnet_lite0.py`, `release_checksums.py`. Se publican como archivos de un release y se descargan desde Ajustes → Motor de escaneo (carpeta configurable).

## Modelos (Python, sin entrenamiento)

`tools/model/` convierte y verifica modelos publicados; ver su README. El script de entrenamiento anterior se eliminó.

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
* 68 pruebas (unitarias en `app/src/test` + integración ligera con respuestas simuladas): parser y Regex por juego, valoración, CSV, adaptadores de catálogo, llaves estables y re-vinculación, estados de los motores, geometría de la carta, decodificador YOLO (formatos y letterbox), post-proceso DB/CTC de PaddleOCR, índice vectorial en RAM, refinado de esquinas sobre imágenes sintéticas;
* compilan contra los JAR reales de TensorFlow Lite, ONNX Runtime y Android: `YoloCardDetector`, `PaddleOcrEngine`, `PerspectiveCropper`, `ScanPipeline`;
* `export_efficientnet_lite0.py` (ruta Keras) se ejecutó de punta a punta con TensorFlow 2.21 (pesos aleatorios: los repositorios de modelos no son accesibles aquí).

Antes de publicar: compila en Android Studio, corrige cualquier error menor de API, y prueba en dispositivo real el escáner, el arrastrar y soltar, Nearby (dos teléfonos con Google Play Services) y las fuentes con red real.

## Aviso legal

Herramienta independiente y no oficial. Los nombres de juegos e imágenes pertenecen a sus dueños y se usan solo para identificar cartas. Los emblemas de la app son generados (sin logos de terceros). Los precios son informativos, no asesoría financiera.


## Idioma, anuncios e icono

- **Idioma**: Ajustes → *Idioma*. Español, inglés, francés, alemán, portugués, chino (simplificado), japonés, ruso e hindi, más «predeterminado del sistema». Usa `AppCompatDelegate.setApplicationLocales` (por eso `MainActivity` es `AppCompatActivity`): la interfaz cambia al instante, sin reiniciar, y el idioma se recuerda (en Android 13+ también aparece en los ajustes del sistema, gracias a `locales_config.xml`). Cada idioma tiene su `res/values-xx/strings.xml`; `LanguageResourcesTest` comprueba que todos tengan las mismas claves y marcadores (`%1$d`...).
- **Anuncios (AdMob)**: un banner adaptativo anclado bajo la app (no tapa contenido). Antes de pedir ningún anuncio se ejecuta el formulario de consentimiento de **Google UMP**; en las regiones donde es obligatorio, Ajustes → *Publicidad* ofrece reabrirlo. Ahora mismo se usan los **IDs de prueba** de Google:
  - App ID (`AndroidManifest.xml`): `ca-app-pub-3940256099942544~3347511713`
  - Banner (`ADMOB_BANNER_UNIT_ID` en `app/build.gradle.kts`): `ca-app-pub-3940256099942544/6300978111`
  - **Antes de publicar**: sustituye ambos por los de tu cuenta de AdMob, declara el *ID de publicidad* y los anuncios en Play Console (Contenido de la app → Anuncios / Seguridad de los datos) y enlaza tu política de privacidad.
- **Icono**: `res/drawable-nodpi/ic_launcher_foreground.png` (adaptativo, fondo `#FF681C`) y versión monocromática en vector; el icono de 512 px para la ficha de Play Store está en `docs/store/ic_launcher_512.png`.
