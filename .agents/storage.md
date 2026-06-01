# 对象存储使用指南

## 目录结构

APK 按以下目录结构上传到对象存储：

```
fcitx5-android/
└── {branch}/
    └── {version}/
        ├── arm64-v8a.apk
        ├── armeabi-v7a.apk
        ├── x86.apk
        ├── x86_64.apk
        └── CHANGES.txt
```

## 上传脚本

```python
from coze_coding_dev_sdk.s3 import S3SyncStorage
import os, glob

storage = S3SyncStorage(
    endpoint_url=os.getenv("COZE_BUCKET_ENDPOINT_URL"),
    access_key="",
    secret_key="",
    bucket_name=os.getenv("COZE_BUCKET_NAME"),
    region="cn-beijing",
)

branch = "your-branch"
version = "version-hash"
apk_dir = "app/build/outputs/apk/debug"

# 上传 CHANGES.txt
changes = """变更说明..."""
storage.upload_file(
    file_content=changes.encode("utf-8"),
    file_name=f"fcitx5-android/{branch}/{version}/CHANGES.txt",
    content_type="text/plain",
)

# 上传 APK
for abi in ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]:
    matches = glob.glob(f"{apk_dir}/org.fcitx.fcitx5.android-{version}-{abi}-debug.apk")
    if not matches:
        continue
    with open(matches[0], "rb") as f:
        storage.upload_file(
            file_content=f.read(),
            file_name=f"fcitx5-android/{branch}/{version}/{abi}.apk",
            content_type="application/vnd.android.package-archive",
        )

# 删除文件
storage.delete_file(file_key="fcitx5-android/branch/version/file.apk")

# 获取下载链接
url = storage.generate_presigned_url(file_key=key, expire_time=86400)
```
