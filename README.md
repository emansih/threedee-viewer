This is a Kotlin Multiplatform project targeting Android, Desktop (JVM), Web, built with the [Kotlin Toolchain](https://kotlin-toolchain.org/dev/).

## ThreeDee Viewer

Upload a GLB and the app renders it with [SceneView](https://sceneview.github.io/). Click (or tap) any surface of the model, choose a PNG or JPEG, and it is printed on that surface's material.

- **Printing:** each material keeps its own image; clicking it again replaces it, and **Remove images** clears them all. A drag orbits the camera and never opens the dialog. The image is stretched over the material's own UVs whatever its aspect ratio; transparent pixels show the material's own colour. A material without texture coordinates can't take an image. On the bottle's `hero_print` that means once around, centred on the front, from the base edge to the cap joint (236 × 111 mm, so a 2.13 : 1 image such as 2360 × 1110 px goes on undistorted).
- **Picking:** the viewer reports the camera's ray through the click, and [`MaterialPicker`](shared/src/glb/MaterialPicker.kt) intersects it with the GLB's own triangles, so every platform picks the same material. Draco-compressed meshes can't be picked.
- **How:** [`MaterialPrint`](shared/src/glb/MaterialPrint.kt) embeds each image in the GLB bytes as its material's base colour texture, so every renderer shows the same result.
- **Renderers:** SceneView's Compose viewer (`sceneview-compose`) on Android and desktop. It has no Kotlin/Wasm variant, so the web app draws with SceneView's `sceneview.js` (Filament.js), loaded from sceneview.github.io by [index.html](webApp/resources/index.html), on a canvas laid over the Compose UI ([ModelViewer.wasmJs.kt](shared/src@wasmJs/viewer/ModelViewer.wasmJs.kt)). The web app needs internet access for it.
- **Lighting:** SceneView's desktop viewer has no light from all around by default, so the side facing away from its sun would render black; desktop lights the model with a neutral grey studio, [studio.hdr](shared/resources@jvm/environments/studio.hdr). Android and the web use SceneView's own default environment.
- JVM code targets Java 21, the oldest JDK SceneView's desktop renderer runs on.

### Sharing a render by URL

No cloud storage and no stored models: the GLB and the images all travel inside the link; the server only serves the app.

```powershell
.\kotlin.bat build -m webApp
node webApp\server\serve.mjs 8080      # needs Node 18+
```

- **Making a link:** in the web app, upload a GLB and (optionally) put images on it, then press **Copy link**. The model and each image with its material are gzipped into one base64url string, `http://127.0.0.1:8080/v?=<base64url>`. The images are shrunk together to fit the size limit (JPEG, or PNG when one has transparency).
- **Opening it:** the app unpacks the query, shows the model and prints each image on its material. Older links holding one image put it on `hero_print`. The path is ignored.
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
