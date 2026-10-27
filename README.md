This is a Kotlin Multiplatform project targeting Android, Desktop (JVM), Web, built with the [Kotlin Toolchain](https://kotlin-toolchain.org/dev/).

## ThreeDee Viewer

Upload a GLB (for example a bottle with a `hero_print` material) and the app renders it with [SceneView](https://sceneview.github.io/). Then upload a PNG or JPEG and it is printed on the bottle.

- **Print area:** the material named `hero_print`. The image is stretched to fill it: once around, centred on the front, from the base edge to the cap joint, whatever its aspect ratio; its left and right edges meet at the back. The area is 236 × 111 mm, so a 2.13 : 1 image (e.g. 2360 × 1110 px) goes on undistorted. Transparent pixels show the material's own colour. A GLB without `hero_print` still renders, but can't take an image.
- **How:** [`BottlePrint`](shared/src/glb/BottlePrint.kt) embeds the image in the GLB bytes as `hero_print`'s base colour texture, mapped onto the print area's own UVs, so every renderer shows the same result.
- **Renderers:** SceneView's Compose viewer (`sceneview-compose`) on Android and desktop. It has no Kotlin/Wasm variant, so the web app draws with SceneView's `sceneview.js` (Filament.js), loaded from sceneview.github.io by [index.html](webApp/resources/index.html), on a canvas laid over the Compose UI ([ModelViewer.wasmJs.kt](shared/src@wasmJs/viewer/ModelViewer.wasmJs.kt)). The web app needs internet access for it.
- **Lighting:** SceneView's desktop viewer has no light from all around by default, so the side facing away from its sun would render black; desktop lights the model with a neutral grey studio, [studio.hdr](shared/resources@jvm/environments/studio.hdr). Android and the web use SceneView's own default environment.
- JVM code targets Java 21, the oldest JDK SceneView's desktop renderer runs on.

### Sharing a render by URL

No cloud storage and no stored models: the GLB and the image both travel inside the link; the server only serves the app.

```powershell
.\kotlin.bat build -m webApp
node webApp\server\serve.mjs 8080      # needs Node 18+
```

- **Making a link:** in the web app, upload a GLB and (optionally) an image, then press **Copy link**. Both are gzipped into one base64url string, `http://127.0.0.1:8080/v?=<base64url>`. The image is shrunk to fit the size limit (JPEG, or PNG when it has transparency).
- **Opening it:** the app unpacks the query, shows the model and prints the image on it. The path is ignored.
- **Limits:** the whole model is in the URL, and Chrome's limit is 2 MB, so only GLBs of about 1 MB gzipped (a bit over 1.3 MB as base64) or less fit. The server accepts headers up to 8 MB.

### Windows installer

```powershell
.\desktopApp\package-windows.ps1                   # MSI, needs the WiX Toolset
.\desktopApp\package-windows.ps1 -Type exe         # EXE installer, needs the WiX Toolset
.\desktopApp\package-windows.ps1 -Type app-image   # portable folder with "ThreeDee Viewer.exe"
```

It uses `jpackage` from the PATH (JDK 21+; pass `-Jpackage <path>` for another one) and writes to `build\jpackage`. JDK 23's jpackage needs WiX 3: the script uses `%LOCALAPPDATA%\Programs\wix314` (the unpacked `wix314-binaries.zip` from the WiX GitHub releases, which needs no admin rights or .NET 3.5) when it's there, or pass `-WixDir`. It ships the libraries as separate jars rather than the toolchain's executable jar, because that jar keeps one library per file name and loses Compose's runtime to a same-named stub.

- [/androidApp](./androidApp) contains the Android application.
- [/desktopApp](./desktopApp) contains the desktop (JVM) application.
- [/shared](./shared) holds the code shared across your applications — Compose UI, business logic, and platform-specific implementations. [src](./shared/src) is for common code; the sibling `src@<platform>` folders (for example `src@android`) hold code compiled only for the platform named in the folder.
- [/webApp](./webApp) contains the web application, compiled to WebAssembly with Kotlin/Wasm.

The `kotlin` (macOS/Linux) and `kotlin.bat` (Windows) scripts in the project root are self-bootstrapping wrappers for the Kotlin Toolchain: they download the pinned toolchain version on first use, so no separate installation is required. Build the whole project with `./kotlin build`.

### Running

Use the run configurations provided by the run widget in your IDE's toolbar. You can also run each module from the command line:

- Android app: `./kotlin run -m androidApp`
- Desktop app: `./kotlin run -m desktopApp`
- Web app: `./kotlin run -m webApp`

### Testing

Run the project's tests with `./kotlin test`, or `./kotlin test -m <module>` for a single module.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html), [Kotlin Toolchain](https://kotlin-toolchain.org/dev/), [Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/), [Kotlin/Wasm](https://kotl.in/wasm/).

## License

[GNU Affero General Public License v3.0](LICENSE).
