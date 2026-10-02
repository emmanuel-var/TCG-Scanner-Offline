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
| Bases de datos asiáticas | Pokémon Japón con OCR japonés (ML Kit) y fuentes PriceCharting / TCGdex |

## Pantallas

1. **Hub de juegos** – cuadrícula con cada TCG activado; al elegir uno toda la app se filtra a ese juego.
2. **Portafolio** – gráfica de líneas dibujada localmente (7D/30D/90D/1A/Todo, con arrastre para ver valores), Raw vs Graded, última sincronización, accesos a *Mis mazos* y *Más valiosas*.
3. **Escáner contextual offline** – CameraX + ML Kit (latino / japonés) buscando solo en el juego activo; búsqueda manual; identificación por ilustración (hash perceptual y, opcionalmente, TensorFlow Lite).
4. **Gestor de mazos** – agrupado por tipo, arrastrar y soltar (resultados → mazo/sideboard, y entre zonas), botones +/− accesibles, faltantes en rojo como lista de deseos con costo estimado.
5. **Catálogo y Master Sets** – sets con porcentaje, detalle con cartas faltantes en gris, alta de cartas propias.
6. **Trade Binder** – carrusel con el valor total disponible para intercambio.
7. **Ajustes** – sincronizar precios, CSV, respaldo/restauración JSON, claves de API opcionales, índice de ilustraciones, importar catálogo, borrar datos.
8. **Intercambio P2P** – Google Nearby Connections (Bluetooth / Wi-Fi Direct, sin internet): emparejamiento con código, comparación de binders y veredicto justo/injusto con **tus** precios locales.

## Arquitectura

```
app/src/main/java/com/tcgscanner/offline
├── core/        juegos, variantes, utilidades de texto
├── data/
│   ├── db/      Room: cartas, precios, graded, colección, mazos, snapshots, firmas
│   ├── remote/  Http (OkHttp) + fuentes de catálogo con fallback
│   ├── repo/    catálogo/sincronización, colección, portafolio, mazos, CSV, respaldo
│   └── prefs/   DataStore (ajustes y claves opcionales)
├── scanner/     parser OCR, matcher, analizador CameraX, firmas visuales, TFLite
├── trade/       Nearby Connections + protocolo de intercambio
├── work/        WorkManager: sincronización diaria
└── ui/          Compose (Material 3), navegación, pantallas
```

* **Sin framework de DI**: `AppContainer` manual.
* **Datos de usuario a salvo**: no hay claves foráneas desde la colección hacia el catálogo, así que refrescar el catálogo nunca borra tu colección.
* **Sync**: cada juego tiene una lista ordenada de fuentes (principal → respaldos). Se omiten las que requieren claves no configuradas. Los datos se guardan por lotes mientras se descargan (Scryfall se procesa en *streaming*).
* **Snapshots del portafolio**: uno por hora como máximo; el último valor de cada hora gana. La gráfica añade el valor «en vivo».

## Fuentes de datos

| Juego | Orden de fuentes |
|---|---|
| Pokémon (EN) | Pokémon TCG API → TCGplayer\* → TCGdex → catálogo propio |
| One Piece | OPTCG API → TCGplayer\* → catálogo propio |
| Magic | Scryfall (bulk) → MTGJSON → catálogo propio |
| Yu-Gi-Oh! | YGOPRODeck → catálogo propio |
| Lorcana | Lorcast → Lorcana API → catálogo propio |
| Riftbound, Gundam, Fusion World | TCGplayer\* → catálogo propio |
| Pokémon Japón | PriceCharting\* (incluye precios graduados) → TCGdex (ja) → catálogo propio |
| Digimon | DigimonCard.io → TCGplayer\* → catálogo propio |

\* Requieren credenciales propias que el usuario pega en Ajustes (se guardan solo en el dispositivo).

**Importante – APIs de la lista original no incluidas:** Limitless TCG, Dreamborn, Yugipedia, Pokellector y los volcados «Bandai TCG Community JSON» no se integraron porque no existe (o no pude verificar) un endpoint público de catálogo estable para ellos. En su lugar:
* se usaron alternativas con endpoints públicos conocidos (OPTCG API, Lorcast, Lorcana API, TCGdex);
* cualquier juego admite un **catálogo propio** (`docs/CATALOG_FORMAT.md`): archivo JSON importable o URL https como última fuente. Así un volcado de la comunidad (p. ej. de GitHub) se conecta sin tocar código.

Los formatos de respuesta de las APIs se implementaron de forma defensiva a partir de su documentación pública; **verifícalos contra las respuestas reales** (ver «Estado de verificación»).

## Escáner

1. **ML Kit Text Recognition** (modelo incluido en el APK, offline): lee nombre y número impreso (`OP01-120`, `025/198`, `LOB-EN005`…). `CardTextParser` extrae candidatos y `ScanMatcher` los cruza en Room solo para el juego activo (número exacto + similitud de nombre con tolerancia a errores de OCR). Hacen falta 2 lecturas consistentes antes de fijar.
2. **Resolución de variantes**: *bottom sheet* con versión, estado/slab, cantidad y precio manual opcional, más «otras ediciones de esta carta».
3. **Cartas de arte completo**: botón «Identificar por ilustración». Usa firmas precalculadas (dHash + aHash de la zona de arte) o, si incluyes `assets/models/card_embedder.tflite`, embeddings TFLite (ver `docs/SCANNER_MODEL.md`). Las firmas se generan una sola vez desde Ajustes (opcional, con descarga de imágenes).

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
* 18 pruebas unitarias en `app/src/test` (texto, parser OCR, valoración, CSV, veredicto de intercambio, catálogo propio);
* pruebas adicionales de las fuentes contra respuestas simuladas.

Antes de publicar: compila en Android Studio, corrige cualquier error menor de API, y prueba en dispositivo real el escáner, el arrastrar y soltar, Nearby (dos teléfonos con Google Play Services) y las fuentes con red real.

## Aviso legal

Herramienta independiente y no oficial. Los nombres de juegos e imágenes pertenecen a sus dueños y se usan solo para identificar cartas. Los emblemas de la app son generados (sin logos de terceros). Los precios son informativos, no asesoría financiera.
