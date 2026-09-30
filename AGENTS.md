# AGENTS.md — imagify

画像の読み込み・変換・書き出しを行う Java ライブラリ（Maven / JDK 24）。
AVIF・WebP・JPEG は GitHub Release から遅延取得するネイティブライブラリを Java FFM
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


## コーディング規約（既存コードで確認できるもの）

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
- ネイティブは jar に同梱しない。各フォーマットの release タグは
  `src/main/resources/imagify/<codec>/native/native.properties` の `tag` が単一ソースで、
  ランタイム（`NativeRepository`）とネイティブ配布 workflow の両方がこれを読む。タグは
  immutable（同一タグの再発行は workflow が拒否）なので、同一バージョンの再ビルドは
  `-2`/`-3` のような dash suffix を付けて同じ commit で `native.properties` を更新する。
- ネイティブの再ビルドは `.github/workflows/{webp,avif,jpegli}-natives.yml` を
  `gh workflow run` で回すと Release assets としてそのまま公開される（`workflow_dispatch`
  のみ、cron なし）。公開前に assets と同じ実体が手元に必要ならローカルのキャッシュ
  （既定 `~/.imagify/natives/<format>/<tag>/`）を確認する。`mvn test` だけでは新しい
  シンボルは入らない。
- 遅延ダウンロード周りのプロパティ: `imagify.native.cache`（キャッシュ場所）、
  `imagify.native.download=false`（DL 禁止）、`imagify.native.url`（配信元 override）、
  `imagify.<fmt>.library`（明示パス）、`imagify.<fmt>.bundled=false`（managed 無効化）。
- FFM を使うため、JDK 24 以降では `--enable-native-access=ALL-UNNAMED` を付けると
  警告が出ない（surefire の `argLine` には入っていない）。
- 配布バイナリ（Release assets）のライセンス: libwebp / libyuv / libsharpyuv は BSD-3-Clause、
  libavif / libaom は BSD-2-Clause、jpegli は BSD-3-Clause、highway は Apache-2.0 /
  BSD-3-Clause デュアル、Little-CMS は MIT。各ライセンスの原文は
  `src/main/resources/imagify/<codec>/native/LICENSE-*.txt` に置いて jar に同梱する
  （`NativeRepositoryTest` が存在をピン留め）。再配布時は upstream の LICENSE を保持する。

## 禁止事項（既存コードから確認できるもの）

- `pom.xml` / `README.md` / `LICENSE.txt` を生成物と知らずに直接編集しない
  （生成元は `src/project/java/Project.java`）。
- ネイティブの共有ライブラリをリネームしない。衝突回避のため `jpegli` /
  `imagifywebp` / `imagifyavif` という名前に固定されている。

## コミットメッセージ

- Conventional Commits を採用する（`<type>: <description>` 形式。例:
  `feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `chore:`）。
- `release-please`（`.github/workflows/build.yml`、`release-type: simple`）がこの形式を
  前提にリリースノートとバージョンを生成する。
