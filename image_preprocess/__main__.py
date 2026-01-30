"""
CLI entry point: python -m image_preprocess input.jpg [output.jpg]
Reads from stdin (bytes) if no input file; writes to stdout (bytes) if no output file.
"""

import argparse
import sys
from pathlib import Path

from .preprocess import MAX_DIMENSION, JPEG_QUALITY, preprocess


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Preprocess invoice image: EXIF rotate, crop, deskew, resize, JPEG."
    )
    parser.add_argument(
        "input",
        nargs="?",
        help="Input image path. If omitted, read raw image bytes from stdin.",
    )
    parser.add_argument(
        "output",
        nargs="?",
        help="Output JPEG path. If omitted, write JPEG bytes to stdout.",
    )
    parser.add_argument(
        "--max-dimension",
        type=int,
        default=MAX_DIMENSION,
        help=f"Max side length in pixels (default {MAX_DIMENSION}).",
    )
    parser.add_argument(
        "--quality",
        type=int,
        default=JPEG_QUALITY,
        help=f"JPEG quality 1-100 (default {JPEG_QUALITY}).",
    )
    parser.add_argument("--no-crop", action="store_true", help="Skip invoice crop.")
    parser.add_argument("--no-deskew", action="store_true", help="Skip deskew.")
    args = parser.parse_args()

    try:
        if args.input is None:
            source = sys.stdin.buffer.read()
            if not source:
                print("Error: no input (empty stdin)", file=sys.stderr)
                return 1
        else:
            source = Path(args.input)

        out_bytes = preprocess(
            source,
            max_dimension=args.max_dimension,
            jpeg_quality=args.quality,
            crop_invoice=not args.no_crop,
            deskew=not args.no_deskew,
        )

        if args.output is None:
            sys.stdout.buffer.write(out_bytes)
        else:
            Path(args.output).write_bytes(out_bytes)
        return 0
    except FileNotFoundError as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
