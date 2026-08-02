from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "sample_books" / "墨知压力样例-600章.txt"


def main() -> None:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT.open("w", encoding="utf-8-sig", newline="\n") as book:
        for chapter in range(1, 601):
            if chapter > 1:
                book.write("\n")
            book.write(f"第{chapter}章 墨知长篇测试{chapter}\n")
            book.write("这是用于验证千章级导入、章节识别和目录跳转的正文。\n")
            book.write("雨落在书页上，灯火从文字深处亮起。\n")
    print(OUTPUT)


if __name__ == "__main__":
    main()
