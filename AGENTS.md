# AGENTS.md — imagify

画像の読み込み・変換・書き出しを行う Java ライブラリ（Maven / JDK 24）。
AVIF・WebP・JPEG は jar に同梱したネイティブライブラリを Java FFM
(`java.lang.foreign`) 経由で呼び、PNG・GIF・BMP・ICO は JDK の ImageIO、
SVG は JSVG で読み取り専用に扱う。

## よく使うコマンド

```sh
mvn test                              # 全テスト (JUnit 5 / surefire)
mvn -Dtest=ImagifyTest test           # クラスを指定してテスト
mvn clean test                        # クリーンビルドしてテスト
mvn -DskipTests compile               # コンパイルのみ
mvn -DskipTests package               # jar を作る
mvn dependency:tree                   # 依存の確認

gh workflow run webp-natives.yml      # ネイティブ再ビルド (同様に avif / jpegli)
```

- Maven を使う。`mvnw` は無い。
- JDK 24 が必要。`pom.xml` は `<release>24</release>`、`jitpack.yml` は
  `openjdk24`、`.github/workflows/build.yml` は Java 24 を設定している。

## ビルド方法

- 通常のビルドは Maven。ネイティブライブラリは `src/main/resources/imagify/*/native/`
  に同梱済みなので、Java のビルドでネイティブをコンパイルする必要はない。
- CI (`.github/workflows/build.yml`) は Bee で
  `bee install doc:site maven:pom ci:readme ci:license` を実行し、`target/site` を
  GitHub Pages へデプロイ、生成物を "update repository info" として auto-commit、
  `release-please` でリリースする。
- JitPack (`jitpack.yml`) も Bee で `bee install maven --skip test` を実行する。
- バージョンは `version.txt`にあり、`src/project/java/Project.java`
  が `ref("version.txt")` で読む。

## テスト方法

- JUnit 5 (Jupiter) + surefire 3.5.2。`argLine` は `-ea -Dfile.encoding=UTF-8`。
- surefire の既定の命名に一致するクラスだけが `mvn test` で実行される
  (`*Test` など)。`FormatComparison` / `ResizeComparison`（比較レポート生成）と
  `ResizeTest`（`main` ハーネス）は `mvn test` の対象外。
- ネイティブが無い環境では各コーデックが `isAvailable()` /
  `getUnavailableReason()` を見て `assumeTrue` でスキップする。ネイティブが無くても
  テストは失敗しない。
- テストによっては `target/test-output/` などへレポート用の画像を書き出す。

## 使用ライブラリ

- `com.github.weisj:jsvg:2.2.0`（compile）— SVG のラスタライズ
- `com.github.teletha:antibug:1.14.0`（test）
- Java FFM（`java.lang.foreign`）— WebP / AVIF / JPEG のバインディング。JNA 依存は無い。
- ネイティブ（jar に同梱、`src/main/native` の CMake でビルド）
    - libwebp
    - libavif（libaom・libyuv・libsharpyuv を静的リンク）
    - jpegli（highway を静的リンク）

## コーディング規約（既存コードで確認できるもの）

- Java のインデントは 4 スペース（`pom.xml` はタブ）。
- 各ソースの冒頭に MIT ライセンスヘッダを置く。
- 公開 API には Javadoc を付ける。このコードベースは「何をするか」より
  「なぜそうするか」を長文で説明するスタイル。
- ユーティリティクラスは `final` + private コンストラクタ
- FFM バインディングは `MethodHandle` をフィールドに持ち `SymbolLookup` から解決する。
  新しいシンボルは任意扱い（`null` ハンドル）にして存在チェックしてから呼ぶ
  （`WebpLibrary.resolveOptionalSymbol`、`AvifShim.optionalDowncall`）。
- テストは `System.out` に進捗を出さない。例外は比較レポートを表出力する
  `FormatComparison` と `ResizeComparison`。

## 変更時に注意すべき点

- `pom.xml` と `README.md`（および `LICENSE.txt`）は `src/project/java/Project.java`
  から Bee（`maven:pom`, `ci:readme`, `ci:license`）が生成する。直接編集しても CI の
  auto-commit で戻されることがある。設定や説明を変えるときは `Project.java` を直す。
- ネイティブを変更したら `src/main/native/<codec>/CMakeLists.txt` の
  シンボルエクスポート一覧（`IMAGIFY_*_SYMBOLS`）を更新する。Windows は `.def`、
  macOS は `_imagify_*` の glob、Linux は version script を使う。
- ネイティブの再ビルドは `.github/workflows/{webp,avif,jpegli}-natives.yml` を
  `gh workflow run` で回し、成果物を `src/main/resources/imagify/<codec>/native/` に
  置く。ローカルで `mvn test` するだけでは新しいシンボルは入らない。
- Java 側は新しいシンボルを任意扱いにして、古い同梱ライブラリでもロードでき、
  無ければ従来経路へフォールバックさせる。
- C ABI のヘッダを互換でない形で変えたら `IMAGIFY_JPEGLI_ABI_VERSION` /
  `IMAGIFY_WEBP_ABI_VERSION` を上げ、`JpegliLibrary.ABI_VERSION` /
  `WebpLibrary.ABI_VERSION` と合わせる。ロード時に照合され、不一致は拒否される。
- AVIF は ABI 番号ではなく `AvifCodec.supportedVersions()` が示す libavif の範囲
  (`1.0.0`〜`1.4.x`) を前提にする。
- FFM を使うため、JDK 24 以降では `--enable-native-access=ALL-UNNAMED` を付けると
  警告が出ない（surefire の `argLine` には入っていない）。
- 同梱バイナリのライセンス: libwebp は BSD-3-Clause、jpegli/highway は Apache-2.0、
  Little-CMS は MIT。再配布時は upstream の LICENSE を保持する。
- `target/` はビルド出力で git 管理外。ソースとネイティブのリソースだけをコミットする。

## 禁止事項（既存コードから確認できるもの）

- JNA を依存に戻さない。WebP / AVIF / JPEG のバインディングはすべて FFM。
- `pom.xml` / `README.md` / `LICENSE.txt` を生成物と知らずに直接編集しない
  （生成元は `src/project/java/Project.java`）。
- ネイティブの共有ライブラリをリネームしない。衝突回避のため `jpegli` /
  `imagifywebp` / `imagifyavif` という名前に固定されている。
- ABI バージョンを上げずに、互換でない形で C ヘッダを変更しない。

## コミットメッセージ

- Conventional Commits を採用する（`<type>: <description>` 形式。例:
  `feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `chore:`）。
- `release-please`（`.github/workflows/build.yml`、`release-type: simple`）がこの形式を
  前提にリリースノートとバージョンを生成する。
