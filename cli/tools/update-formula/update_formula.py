import argparse
import hashlib
import os
import textwrap
from pathlib import Path

from github import Github, GithubException

TAP_REPO = "medusa-software-hq/homebrew-tap"
RELEASES_REPO = "medusa-software-hq/flow-releases"
FORMULA_PATH = "Formula/flow.rb"


def compute_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def render_formula(version: str, sha256: str) -> str:
    url = f"https://github.com/{RELEASES_REPO}/releases/download/{version}/flow-cli.jar"
    return textwrap.dedent(f"""\
        class Flow < Formula
          desc "Flow CLI"
          homepage "https://github.com/medusa-software-hq/flow"
          url "{url}"
          sha256 "{sha256}"
          version "{version}"

          depends_on "openjdk@21"

          def install
            libexec.install "flow-cli.jar"
            # java_version pins the launcher to the openjdk@21 we depend on and build against.
            # Without it, write_jar_script defaults to "any JDK" — so the declared dependency is
            # installed but never used, and the CLI runs on whatever JDK is newest (e.g. JDK 24),
            # where Netty's sun.misc.Unsafe calls print JEP 498 deprecation warnings over the output.
            bin.write_jar_script libexec/"flow-cli.jar",
                                 "ms-flow",
                                 "--enable-native-access=ALL-UNNAMED",
                                 java_version: "21"
          end
        end
    """)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--jar", required=True, type=Path)
    args = parser.parse_args()

    token = os.environ["GITHUB_TOKEN"]
    sha256 = compute_sha256(args.jar)
    formula = render_formula(args.version, sha256)
    message = f"Update Flow CLI to {args.version}"

    repo = Github(token).get_repo(TAP_REPO)

    try:
        existing = repo.get_contents(FORMULA_PATH)
        repo.update_file(FORMULA_PATH, message, formula, existing.sha)
    except GithubException as e:
        if e.status != 404:
            raise
        repo.create_file(FORMULA_PATH, message, formula)


if __name__ == "__main__":
    main()
