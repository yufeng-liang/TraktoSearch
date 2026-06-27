package com.tracktosearch.data.remote.panhub

enum class ChannelGroup(val id: String, val displayName: String) {
    QUARK("quark", "夸克"),
    BAIDU("baidu", "百度"),
    ALIYUN("aliyun", "阿里"),
    ONENONE("115", "115"),
    OTHER("other", "其他")
}

enum class PanHubChannel(
    val id: String,
    val displayName: String,
    val group: ChannelGroup
) {
    // 夸克组
    QUARK_SHARE_CHANNEL("Quark_Share_Channel", "Quark_Share_Channel", ChannelGroup.QUARK),
    QUARKSHARE("quarkshare", "quarkshare", ChannelGroup.QUARK),
    QUARK_MOVIES("Quark_Movies", "Quark_Movies", ChannelGroup.QUARK),
    NEW_QUARK("NewQuark", "NewQuark", ChannelGroup.QUARK),
    YPQUARK("ypquark", "ypquark", ChannelGroup.QUARK),
    UCQUARK("ucquark", "ucquark", ChannelGroup.QUARK),
    KUAKECLOUD("kuakeyun", "kuakeyun", ChannelGroup.QUARK),

    // 百度组
    BAIDUCLOUDDISK("BaiduCloudDisk", "BaiduCloudDisk", ChannelGroup.BAIDU),
    BAIDUYUN("baiduyun", "baiduyun", ChannelGroup.BAIDU),
    BDWPZHPD("bdwpzhpd", "bdwpzhpd", ChannelGroup.BAIDU),

    // 阿里组
    SHARE_ALIYUN("share_aliyun", "share_aliyun", ChannelGroup.ALIYUN),
    SHAREALIYUN("shareAliyun", "shareAliyun", ChannelGroup.ALIYUN),
    ALIYUNYS("aliyunys", "aliyunys", ChannelGroup.ALIYUN),
    ALIYUNDRIVE_SHARE_CHANNEL("AliyunDrive_Share_Channel", "AliyunDrive_Share_Channel", ChannelGroup.ALIYUN),
    ALIYUN_4K_MOVIES("Aliyun_4K_Movies", "Aliyun_4K_Movies", ChannelGroup.ALIYUN),
    IALIYUN("iAliyun", "iAliyun", ChannelGroup.ALIYUN),
    NEW_ALIPAN("NewAliPan", "NewAliPan", ChannelGroup.ALIYUN),
    ALYP_TV("alyp_TV", "alyp_TV", ChannelGroup.ALIYUN),
    ALYP_4K_MOVIES("alyp_4K_Movies", "alyp_4K_Movies", ChannelGroup.ALIYUN),
    ALYP_1("alyp_1", "alyp_1", ChannelGroup.ALIYUN),
    ALYP_ANIMATION("alyp_Animation", "alyp_Animation", ChannelGroup.ALIYUN),
    ALYP_JLP("alyp_JLP", "alyp_JLP", ChannelGroup.ALIYUN),
    LEOZIYUAN("leoziyuan", "leoziyuan", ChannelGroup.ALIYUN),
    YUNPANPAN("yunpanpan", "yunpanpan", ChannelGroup.ALIYUN),

    // 115组
    LSP115("Lsp115", "Lsp115", ChannelGroup.ONENONE),
    ONENONEFIVE("oneonefivewpfx", "oneonefivewpfx", ChannelGroup.ONENONE),
    TGSEARCHERS115("tgsearchers115", "tgsearchers115", ChannelGroup.ONENONE),
    CHANNEL_SHARES_115("Channel_Shares_115", "Channel_Shares_115", ChannelGroup.ONENONE),
    TYYSZYPYPD("tyysypzypd", "tyysypzypd", ChannelGroup.ONENONE),
    VIP115HOT("vip115hot", "vip115hot", ChannelGroup.ONENONE),
    MAIDANGLAO("Maidanglaocom", "Maidanglaocom", ChannelGroup.ONENONE),

    // 其他组
    TGSEARCHERS3("tgsearchers3", "tgsearchers3", ChannelGroup.OTHER),
    YUNPANXUNLEI("yunpanxunlei", "yunpanxunlei", ChannelGroup.OTHER),
    TIANYIFC("tianyifc", "tianyifc", ChannelGroup.OTHER),
    TXTYZY("txtyzy", "txtyzy", ChannelGroup.OTHER),
    PECCXINPD("peccxinpd", "peccxinpd", ChannelGroup.OTHER),
    GOTOPAN("gotopan", "gotopan", ChannelGroup.OTHER),
    XINGQIUMP4("xingqiump4", "xingqiump4", ChannelGroup.OTHER),
    YUNPANQK("yunpanqk", "yunpanqk", ChannelGroup.OTHER),
    PANJCLUB("PanjClub", "PanjClub", ChannelGroup.OTHER),
    KKXLZY("kkxlzy", "kkxlzy", ChannelGroup.OTHER),
    BAICAOZY("baicaoZY", "baicaoZY", ChannelGroup.OTHER),
    MCPH01("MCPH01", "MCPH01", ChannelGroup.OTHER),
    YSXB48("ysxb48", "ysxb48", ChannelGroup.OTHER),
    JDJDN1111("jdjdn1111", "jdjdn1111", ChannelGroup.OTHER),
    YGGPAN("yggpan", "yggpan", ChannelGroup.OTHER),
    MCPH086("MCPH086", "MCPH086", ChannelGroup.OTHER),
    ZAIHUAYUN("zaihuayun", "zaihuayun", ChannelGroup.OTHER),
    Q66SHARE("Q66Share", "Q66Share", ChannelGroup.OTHER),
    OSCAR_4KMOVIES("Oscar_4Kmovies", "Oscar_4Kmovies", ChannelGroup.OTHER),
    UCWPZY("ucwpzy", "ucwpzy", ChannelGroup.OTHER),
    DIANYINGSHARE("dianyingshare", "dianyingshare", ChannelGroup.OTHER),
    XIANGXIUNBB("XiangxiuNBB", "XiangxiuNBB", ChannelGroup.OTHER),
    YDYPZYFX("ydypzyfx", "ydypzyfx", ChannelGroup.OTHER),
    XX123PAN("xx123pan", "xx123pan", ChannelGroup.OTHER),
    YINGSHIFENXIANG123("yingshifenxiang123", "yingshifenxiang123", ChannelGroup.OTHER),
    ZYFB123("zyfb123", "zyfb123", ChannelGroup.OTHER),
    TYYPZHPD("tyypzhpd", "tyypzhpd", ChannelGroup.OTHER),
    TIANYIRIGENG("tianyirigeng", "tianyirigeng", ChannelGroup.OTHER),
    CLOUDTIANYI("cloudtianyi", "cloudtianyi", ChannelGroup.OTHER),
    HDHHD21("hdhhd21", "hdhhd21", ChannelGroup.OTHER),
    WP123ZY("wp123zy", "wp123zy", ChannelGroup.OTHER),
    YUNPAN139("yunpan139", "yunpan139", ChannelGroup.OTHER),
    YUNPAN189("yunpan189", "yunpan189", ChannelGroup.OTHER),
    YUNPANUC("yunpanuc", "yunpanuc", ChannelGroup.OTHER),
    YYDF_HZL("yydf_hzl", "yydf_hzl", ChannelGroup.OTHER),
    QUANZIYUANSHE("quanziyuanshe", "quanziyuanshe", ChannelGroup.OTHER),
    QIXINGZHENREN("qixingzhenren", "qixingzhenren", ChannelGroup.OTHER),
    TAOXGZY("taoxgzy", "taoxgzy", ChannelGroup.OTHER);
}
