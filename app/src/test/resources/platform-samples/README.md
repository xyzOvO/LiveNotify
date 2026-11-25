# 平台响应脱敏样本（回归测试用）

这些片段只保留解析所需的字段结构与类型，**不含真实主播名、头像 URL、房间号或标题**：
所有 ID 都是连续的小整数，昵称用「平台主播 + 编号」，标题用「测试标题」。

用途：给 `StatusInterpretTest` / `DouyinParserTest` 提供“和真实响应同构”的输入，
避免把线上抓包原样入库，同时锁住字段缺失 / 未知状态 / 空壳页等分支。

- `bili_status.json`：B站 `/room/v1/Room/get_status_info_by_uids` 的 data 项（live_status 0/1/2/未知）
- `douyu_room.json`：斗鱼 `/RoomApi/room/{rid}` 的完整壳（error/data/room_status/start_time）
- `huya_room.json`：虎牙 `/cache.php?m=Live&do=profileRoom` 的完整壳（status/data/liveStatus/realLiveStatus）
- `douyin_reflow.txt`：抖音移动端落地页的转义 RSC 片段（含 room/owner/user 干扰对象）
