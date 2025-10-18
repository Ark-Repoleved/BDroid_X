import re
from collections import namedtuple
from typing import Optional, Union, cast, IO
from pathlib import Path

from .. import config
from ..enums import ArchiveFlags, ArchiveFlagsOld, CompressionFlags
from ..helpers import ArchiveStorageManager, CompressionHelper
from ..helpers.UnityVersion import UnityVersion
from ..streams import EndianBinaryReader, EndianBinaryWriter
from . import File

BlockInfo = namedtuple("BlockInfo", "uncompressedSize compressedSize flags")
DirectoryInfoFS = namedtuple("DirectoryInfoFS", "offset size flags path")
reVersion = re.compile(r"(\d+)\.(\d+)\.(\d+)\w.+")


class BundleFile(File.File):
    format: int
    is_changed: bool
    signature: str
    version_engine: str
    version_player: str
    dataflags: Union[ArchiveFlags, ArchiveFlagsOld]
    decryptor: Optional[ArchiveStorageManager.ArchiveStorageDecryptor] = None
    _uses_block_alignment: bool = False

    def __init__(
        self,
        reader: EndianBinaryReader,
        parent: File,
        name: Optional[str] = None,
        **kwargs,
    ):
        super().__init__(parent=parent, name=name, **kwargs)
        signature = self.signature = reader.read_string_to_null()
        self.version = reader.read_u_int()
        self.version_player = reader.read_string_to_null()
        self.version_engine = reader.read_string_to_null()

        if signature == "UnityArchive":
            raise NotImplementedError("BundleFile - UnityArchive")
        elif signature in ["UnityWeb", "UnityRaw"]:
            m_DirectoryInfo, blocksReader = self.read_web_raw(reader)
        elif signature == "UnityFS":
            m_DirectoryInfo, blocksReader = self.read_fs(reader)
        else:
            raise NotImplementedError(f"Unknown Bundle signature: {signature}")

        self.read_files(blocksReader, m_DirectoryInfo)

    def read_web_raw(self, reader: EndianBinaryReader):
        version = self.version
        if version >= 4:
            self._hash = reader.read_bytes(16)
            self.crc = reader.read_u_int()

        minimumStreamedBytes = reader.read_u_int()
        headerSize = reader.read_u_int()
        numberOfLevelsToDownloadBeforeStreaming = reader.read_u_int()
        levelCount = reader.read_int()
        reader.Position += 4 * 2 * (levelCount - 1)

        compressedSize = reader.read_u_int()
        uncompressedSize = reader.read_u_int()

        if version >= 2:
            completeFileSize = reader.read_u_int()

        if version >= 3:
            fileInfoHeaderSize = reader.read_u_int()

        reader.Position = headerSize

        uncompressedBytes = reader.read_bytes(compressedSize)
        if self.signature == "UnityWeb":
            uncompressedBytes = CompressionHelper.decompress_lzma(uncompressedBytes, True)

        blocksReader = EndianBinaryReader(uncompressedBytes, offset=headerSize)
        nodesCount = blocksReader.read_int()
        m_DirectoryInfo = [
            File.DirectoryInfo(
                blocksReader.read_string_to_null(),
                blocksReader.read_u_int(),
                blocksReader.read_u_int(),
            )
            for _ in range(nodesCount)
        ]

        return m_DirectoryInfo, blocksReader

    def read_fs(self, reader: EndianBinaryReader):
        size = reader.read_long()

        compressedSize = reader.read_u_int()
        uncompressedSize = reader.read_u_int()
        dataflagsValue = reader.read_u_int()

        version = self.parse_version()
        if (
            version < (2020,)
            or (version[0] == 2020 and version < (2020, 3, 34))
            or (version[0] == 2021 and version < (2021, 3, 2))
            or (version[0] == 2022 and version < (2022, 1, 1))
        ):
            self.dataflags = ArchiveFlagsOld(dataflagsValue)
        else:
            self.dataflags = ArchiveFlags(dataflagsValue)

        if self.dataflags & self.dataflags.UsesAssetBundleEncryption:
            self.decryptor = ArchiveStorageManager.ArchiveStorageDecryptor(reader)

        if self.version >= 7:
            reader.align_stream(16)
            self._uses_block_alignment = True
        elif version >= (2019, 4):
            pre_align = reader.Position
            align_data = reader.read((16 - pre_align % 16) % 16)
            if any(align_data):
                reader.Position = pre_align
            else:
                self._uses_block_alignment = True

        start = reader.Position
        if self.dataflags & ArchiveFlags.BlocksInfoAtTheEnd:
            reader.Position = reader.Length - compressedSize
            blocksInfoBytes = reader.read_bytes(compressedSize)
            reader.Position = start
        else:
            blocksInfoBytes = reader.read_bytes(compressedSize)

        blocksInfoBytes = self.decompress_data(blocksInfoBytes, uncompressedSize, self.dataflags)
        blocksInfoReader = EndianBinaryReader(blocksInfoBytes, offset=start)

        uncompressedDataHash = blocksInfoReader.read_bytes(16)
        blocksInfoCount = blocksInfoReader.read_int()

        m_BlocksInfo = [
            BlockInfo(
                blocksInfoReader.read_u_int(),
                blocksInfoReader.read_u_int(),
                blocksInfoReader.read_u_short(),
            )
            for _ in range(blocksInfoCount)
        ]

        nodesCount = blocksInfoReader.read_int()
        m_DirectoryInfo = [
            DirectoryInfoFS(
                blocksInfoReader.read_long(),
                blocksInfoReader.read_long(),
                blocksInfoReader.read_u_int(),
                blocksInfoReader.read_string_to_null(),
            )
            for _ in range(nodesCount)
        ]

        if m_BlocksInfo:
            self._block_info_flags = m_BlocksInfo[0].flags

        if isinstance(self.dataflags, ArchiveFlags) and self.dataflags & ArchiveFlags.BlockInfoNeedPaddingAtStart:
            reader.align_stream(16)

        blocksReader = EndianBinaryReader(
            b"".join(
                self.decompress_data(
                    reader.read_bytes(blockInfo.compressedSize),
                    blockInfo.uncompressedSize,
                    blockInfo.flags,
                    i,
                )
                for i, blockInfo in enumerate(m_BlocksInfo)
            ),
            offset=(blocksInfoReader.real_offset()),
        )

        return m_DirectoryInfo, blocksReader

    def save(self, packer: Optional[Union[str, tuple]] = None, outfile: Optional[Union[str, Path, IO[bytes]]] = None) -> Optional[bytes]:
        writer = EndianBinaryWriter()

        writer.write_string_to_null(self.signature)
        writer.write_u_int(self.version)
        writer.write_string_to_null(self.version_player)
        writer.write_string_to_null(self.version_engine)

        if self.signature == "UnityArchive":
            raise NotImplementedError("BundleFile - UnityArchive")
        elif self.signature in ["UnityWeb", "UnityRaw"]:
            self.save_web_raw(writer)
        elif self.signature == "UnityFS":
            if not packer or packer == "none":
                self.save_fs(writer, 64, 64)
            elif packer == "original":
                self.save_fs(
                    writer,
                    data_flag=self.dataflags,
                    block_info_flag=self._block_info_flags,
                )
            elif packer == "lz4":
                self.save_fs(writer, data_flag=194, block_info_flag=2)
            elif packer == "lzma":
                self.save_fs(writer, data_flag=65, block_info_flag=1)
            elif isinstance(packer, tuple):
                self.save_fs(writer, *packer)
            else:
                raise NotImplementedError("UnityFS - Packer:", packer)
        
        if outfile:
            if isinstance(outfile, (str, Path)):
                with open(outfile, "wb") as f:
                    f.write(writer.bytes)
            else:
                outfile.write(writer.bytes)
        else:
            return writer.bytes

    def save_fs(self, writer: EndianBinaryWriter, data_flag: int, block_info_flag: int):
        data_writer = EndianBinaryWriter()
        files = [
            (
                name,
                f.flags,
                len(f.bytes if isinstance(f, (EndianBinaryReader, EndianBinaryWriter)) else f.save()),
                f.bytes if isinstance(f, (EndianBinaryReader, EndianBinaryWriter)) else f.save()
            )
            for name, f in self.files.items()
        ]

        for _, _, _, file_bytes in files:
            data_writer.write(file_bytes)
        
        file_data = data_writer.bytes
        data_writer.dispose()

        if block_info_flag & self.dataflags.UsesAssetBundleEncryption:
            block_info_flag ^= self.dataflags.UsesAssetBundleEncryption
        if data_flag & self.dataflags.UsesAssetBundleEncryption:
            data_flag ^= self.dataflags.UsesAssetBundleEncryption

        file_data, block_info = CompressionHelper.chunk_based_compress(file_data, block_info_flag)

        block_writer = EndianBinaryWriter(b"\x00" * 0x10)
        block_writer.write_int(len(block_info))
        for block_uncompressed_size, block_compressed_size, block_flag in block_info:
            block_writer.write_u_int(block_uncompressed_size)
            block_writer.write_u_int(block_compressed_size)
            block_writer.write_u_short(block_flag)

        if not data_flag & 0x40:
            raise NotImplementedError("UnityPy always writes DirectoryInfo, so data_flag must include 0x40")

        block_writer.write_int(len(files))
        offset = 0
        for f_name, f_flag, f_len, _ in files:
            block_writer.write_long(offset)
            block_writer.write_long(f_len)
            offset += f_len
            block_writer.write_u_int(f_flag)
            block_writer.write_string_to_null(f_name)

        block_data = block_writer.bytes
        block_writer.dispose()

        uncompressed_block_data_size = len(block_data)

        switch = data_flag & 0x3F
        if switch in CompressionHelper.COMPRESSION_MAP:
            block_data = CompressionHelper.COMPRESSION_MAP[switch](block_data)
        else:
            raise NotImplementedError(f"No compression function in the CompressionHelper.COMPRESSION_MAP for {switch}")

        compressed_block_data_size = len(block_data)

        writer_header_pos = writer.Position
        writer.write_long(0)
        writer.write_u_int(compressed_block_data_size)
        writer.write_u_int(uncompressed_block_data_size)
        writer.write_u_int(data_flag)

        if self._uses_block_alignment:
            writer.align_stream(16)

        if data_flag & 0x80:
            if data_flag & 0x200:
                writer.align_stream(16)
            writer.write(file_data)
            writer.write(block_data)
        else:
            writer.write(block_data)
            if data_flag & 0x200:
                writer.align_stream(16)
            writer.write(file_data)

        writer_end_pos = writer.Position
        writer.Position = writer_header_pos
        writer.write_long(writer_end_pos)
        writer.Position = writer_end_pos

    def save_web_raw(self, writer: EndianBinaryWriter):
        if self.version > 3:
            raise NotImplementedError("Saving Unity Web bundles with version > 3 is not supported")

        file_info_header_size = 4

        for file_name in self.files.keys():
            file_info_header_size += len(file_name.encode()) + 1
            file_info_header_size += 4 * 2

        file_info_header_padding_size = 4 - (file_info_header_size % 4) if file_info_header_size % 4 != 0 else 0
        file_info_header_size += file_info_header_padding_size

        directory_info_writer = EndianBinaryWriter()
        directory_info_writer.write_int(len(self.files))

        file_content_writer = EndianBinaryWriter()
        current_offset = file_info_header_size

        for file_name, f in self.files.items():
            directory_info_writer.write_string_to_null(file_name)
            directory_info_writer.write_u_int(current_offset)

            if isinstance(f, (EndianBinaryReader, EndianBinaryWriter)):
                file_data = f.bytes
            else:
                file_data = f.save()

            file_size = len(file_data)
            directory_info_writer.write_u_int(file_size)

            file_content_writer.write_bytes(file_data)
            current_offset += file_size

        directory_info_writer.write(b"\x00" * file_info_header_padding_size)
        uncompressed_directory_info = directory_info_writer.bytes
        uncompressed_file_content = file_content_writer.bytes

        uncompressed_content = uncompressed_directory_info + uncompressed_file_content
        compressed_content = uncompressed_content
        if self.signature == "UnityWeb":
            compressed_content = CompressionHelper.compress_lzma(uncompressed_content, True)

        header_size = writer.Position + 24
        if self.version >= 2:
            header_size += 4
        if self.version >= 3:
            header_size += 4
        if self.version >= 4:
            header_size += 20
        header_size = (header_size + 3) & ~3

        if self.version >= 4:
            writer.write_bytes(self._hash)
            writer.write_u_int(self.crc)

        writer.write_u_int(header_size + len(compressed_content))
        writer.write_u_int(header_size)
        writer.write_u_int(1)
        writer.write_int(1)

        writer.write_u_int(len(compressed_content))
        writer.write_u_int(len(uncompressed_content))

        if self.version >= 2:
            writer.write_u_int(header_size + len(compressed_content))

        if self.version >= 3:
            writer.write_u_int(file_info_header_size)

        writer.align_stream(4)

        writer.write(compressed_content)

    def decompress_data(
        self,
        compressed_data: bytes,
        uncompressed_size: int,
        flags: Union[int, ArchiveFlags, ArchiveFlagsOld],
        index: int = 0,
    ) -> bytes:
        comp_flag = CompressionFlags(flags & ArchiveFlags.CompressionTypeMask)

        if self.decryptor is not None and flags & 0x100:
            compressed_data = self.decryptor.decrypt_block(compressed_data, index)

        if comp_flag in CompressionHelper.DECOMPRESSION_MAP:
            return cast(
                bytes,
                CompressionHelper.DECOMPRESSION_MAP[comp_flag](compressed_data, uncompressed_size),
            )
        else:
            raise ValueError(f"Unknown compression! flag: {flags}, compression flag: {comp_flag.value}")

    def parse_version(self) -> UnityVersion:
        version = None
        version_str = self.version_engine
        try:
            version = UnityVersion.from_str(version_str)
        except ValueError:
            pass

        if version is None or version.major == 0:
            version_str = config.get_fallback_version()
            version = UnityVersion.from_str(version_str)

        return version
