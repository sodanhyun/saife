"""
Java 문자열 리터럴 복구.

치환 스크립트가 이스케이프 시퀀스를 실제 개행으로 넣어 문자열이 줄을 넘어가 버렸다.
열린 채 끝난 줄을 다음 줄과 합치면서 개행을 이스케이프로 되돌린다.

일회성 도구다. 남겨두는 이유는 같은 사고가 또 날 수 있어서다 —
heredoc 안에서 백슬래시를 다루면 셸이 뭉갠다. 스크립트는 파일로 써서 실행할 것.
"""
import io
import sys

BACKSLASH = chr(92)
QUOTE = '"'
NEWLINE_ESCAPE = BACKSLASH + "n"


def unescaped_quote_count(line):
    """이스케이프되지 않은 큰따옴표 개수. 홀수면 문자열이 열린 채 줄이 끝난 것."""
    count = 0
    i = 0
    while i < len(line):
        ch = line[i]
        if ch == BACKSLASH:
            i += 2
            continue
        if ch == QUOTE:
            count += 1
        i += 1
    return count


def repair(path):
    lines = io.open(path, encoding="utf-8").read().split("\n")
    out = []
    buf = None
    joined = 0

    for line in lines:
        if buf is None:
            if unescaped_quote_count(line) % 2 == 1:
                buf = line
            else:
                out.append(line)
        else:
            buf = buf + NEWLINE_ESCAPE + line.lstrip()
            joined += 1
            if unescaped_quote_count(buf) % 2 == 0:
                out.append(buf)
                buf = None

    if buf is not None:
        out.append(buf)

    io.open(path, "w", encoding="utf-8").write("\n".join(out))
    return joined


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    for p in sys.argv[1:]:
        print(f"{p}: {repair(p)}줄 합침")
