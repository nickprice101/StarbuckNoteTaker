"""Minimal FlatBuffers runtime helpers required for TensorFlow Lite schema parsing."""

from . import util  # noqa: F401
from ._version import __version__  # noqa: F401
from .compat import range_func as compat_range  # noqa: F401
from .table import Table  # noqa: F401
