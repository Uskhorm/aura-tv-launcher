# Aura — a beautiful home screen for Android TV & Fire TV

Aura is a free, open-source launcher for Android TV, Google TV and Fire TV. It has a full-screen
photo Top Shelf, icons that lift and tilt as you move, a Control Center, and an Aerial screensaver.

**Download:** [home-tv-launcher.vercel.app](https://home-tv-launcher.vercel.app) · or [`site/download/Home.apk`](site/download/Home.apk)

![Home screen](site/assets/shots/top0.webp)

## What's in this repo

| Folder | What it is |
| --- | --- |
| [`android/`](android/) | The launcher app (Kotlin + Jetpack Compose). Install guide and build steps in [`android/README.md`](android/README.md) |
| [`site/`](site/) | The download website, deployed to Vercel (plain HTML/CSS/JS, no build step) |
| [`web/`](web/) | An earlier browser-only version for TVs that can't install apps |

## Build the APK

```sh
cd android
./gradlew assembleRelease
cp app/build/outputs/apk/release/app-release.apk ../site/download/Home.apk
```

## Run the website locally

```sh
python3 -m http.server 4321 --directory site
```

## Deploy

The repo root is a Vercel project: `vercel.json` serves the `site/` folder as-is, and pushes to
`main` deploy automatically. To deploy by hand: `vercel --prod`.

## Credits

Photos from [Unsplash](https://unsplash.com) (Unsplash License). Typeface [Inter](https://rsms.me/inter/) (SIL OFL).
Not affiliated with Apple, Google or Amazon.

## License

MIT — see [LICENSE](LICENSE).

Aura is a fork of [ErzenXz/home-tv-launcher](https://github.com/ErzenXz/home-tv-launcher), by ErzenXz, retained under the original MIT license.
