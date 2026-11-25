package com.phy4l.tcfy.glossary

/**
 * Starter glossary packs pre-seeded so users can test immediately
 * or use them as templates for their own courses.
 */
object BuiltInGlossaries {

    val MOLECULAR_BIOLOGY_STARTER = GlossaryPackExport(
        name = "分子生物学核心术语",
        description = "涵盖 DNA 复制、转录翻译、基因调控、表观遗传、基因编辑与常用分子实验技术（含远场同音易错映射）",
        terms = listOf(
            // DNA & Chromatin Structure / Replication
            GlossaryTerm(
                canonicalTerm = "DNA polymerase",
                translation = "DNA聚合酶",
                aliases = listOf("DNA聚合没", "聚合没", "DNA聚和酶"),
                notes = "以模板链催化脱氧核苷酸聚合合成新 DNA 链的酶"
            ),
            GlossaryTerm(
                canonicalTerm = "RNA polymerase",
                translation = "RNA聚合酶",
                aliases = listOf("RNA聚合没", "RNA聚和酶"),
                notes = "催化 DNA 转录合成 RNA 的核心酶（原核核心酶/全酶，真核 Pol I/II/III）"
            ),
            GlossaryTerm(
                canonicalTerm = "Helicase",
                translation = "解旋酶",
                aliases = listOf("解旋没", "解玄酶", "解选酶", "helikase"),
                notes = "消耗 ATP 解开 DNA 双螺旋氢键形成复制叉"
            ),
            GlossaryTerm(
                canonicalTerm = "Topoisomerase",
                translation = "拓扑异构酶",
                aliases = listOf("拓扑异构没", "拓扑一构酶", "拓朴异构酶", "topoisomerace"),
                notes = "消除复制与转录过程中产生的 DNA 超螺旋张力"
            ),
            GlossaryTerm(
                canonicalTerm = "DNA ligase",
                translation = "DNA连接酶",
                aliases = listOf("连接没", "DNA连接没"),
                notes = "催化磷酸二酯键形成，连接冈崎片段或重组 DNA 切口"
            ),
            GlossaryTerm(
                canonicalTerm = "Primase",
                translation = "引物酶",
                aliases = listOf("引物没", "引务酶"),
                notes = "合成短链 RNA 引物为 DNA 聚合酶提供游离 3'-OH"
            ),
            GlossaryTerm(
                canonicalTerm = "Replication fork",
                translation = "复制叉",
                aliases = listOf("复制差", "复制插"),
                notes = "DNA 半保留复制时双链解开形成的 Y 形结构"
            ),
            GlossaryTerm(
                canonicalTerm = "Okazaki fragment",
                translation = "冈崎片段",
                aliases = listOf("刚奇片段", "冈奇片段", "钢崎片段", "okazaki"),
                notes = "后随链（lagging strand）不连续合成的短 DNA 片段"
            ),
            GlossaryTerm(
                canonicalTerm = "Telomerase",
                translation = "端粒酶",
                aliases = listOf("端粒没", "端力酶", "断粒酶", "端立酶"),
                notes = "含 RNA 模板的核糖核蛋白逆转录酶，维持真核染色体末端端粒长度"
            ),
            GlossaryTerm(
                canonicalTerm = "Chromatin",
                translation = "染色质",
                aliases = listOf("染设质", "染色只", "chromatin"),
                notes = "真核细胞核内 DNA 与组蛋白等形成的复合物（常染色质/异染色质）"
            ),
            GlossaryTerm(
                canonicalTerm = "Nucleosome",
                translation = "核小体",
                aliases = listOf("核小提", "合小体", "nucleasome"),
                notes = "染色质基本结构单位：约 147 bp DNA 缠绕组蛋白八聚体"
            ),
            GlossaryTerm(
                canonicalTerm = "Histone",
                translation = "组蛋白",
                aliases = listOf("阻蛋白", "祖蛋白"),
                notes = "富含碱性氨基酸的核蛋白（H2A/H2B/H3/H4 及连接组蛋白 H1）"
            ),

            // Transcription, RNA Processing & Gene Regulation
            GlossaryTerm(
                canonicalTerm = "Transcription",
                translation = "转录",
                aliases = listOf("转路", "专录"),
                notes = "以 DNA 为模板合成 RNA 的过程"
            ),
            GlossaryTerm(
                canonicalTerm = "Transcription factor",
                translation = "转录因子",
                aliases = listOf("转录音子", "转录引子"),
                notes = "结合顺式作用元件调控 RNA 聚合酶招募与转录起始的蛋白质"
            ),
            GlossaryTerm(
                canonicalTerm = "Promoter",
                translation = "启动子",
                aliases = listOf("启动只", "起动子", "启动紫"),
                notes = "位于基因上游、被 RNA 聚合酶识别结合以启动转录的 DNA 序列（如 TATA box）"
            ),
            GlossaryTerm(
                canonicalTerm = "Enhancer",
                translation = "增强子",
                aliases = listOf("增强只", "增墙子"),
                notes = "可远距离、不依赖方向显著提高基因转录效率的顺式作用元件"
            ),
            GlossaryTerm(
                canonicalTerm = "Operon",
                translation = "操纵子",
                aliases = listOf("操纵只", "操作子", "操中子"),
                notes = "原核生物共转录基因调控单元（如乳糖操纵子 lac operon / 色氨酸操纵子 trp operon）"
            ),
            GlossaryTerm(
                canonicalTerm = "Spliceosome",
                translation = "剪接体",
                aliases = listOf("简洁体", "剪接提", "减接体", "splicesome"),
                notes = "由 snRNP 和蛋白组成，催化真核 pre-mRNA 切除内含子并连接外显子"
            ),
            GlossaryTerm(
                canonicalTerm = "Intron",
                translation = "内含子",
                aliases = listOf("内含只", "内寒子", "内函子"),
                notes = "真核基因转录后在 RNA 剪接中被切除的非编码间隔序列"
            ),
            GlossaryTerm(
                canonicalTerm = "Exon",
                translation = "外显子",
                aliases = listOf("外显只", "外线子", "外险子"),
                notes = "经剪接保留在成熟 mRNA 中的序列，可变剪接可产生多种蛋白异构体"
            ),
            GlossaryTerm(
                canonicalTerm = "Alternative splicing",
                translation = "可变剪接",
                aliases = listOf("可变简洁", "可变剪结"),
                notes = "同一 pre-mRNA 按不同外显子组合产生多种成熟 mRNA"
            ),
            GlossaryTerm(
                canonicalTerm = "Polyadenylation",
                translation = "多聚腺苷酸化",
                aliases = listOf("多聚腺甘酸化", "加尾修饰", "polyA"),
                notes = "真核 mRNA 3' 端加上 poly(A) 尾，增强稳定性并促进出核与翻译"
            ),
            GlossaryTerm(
                canonicalTerm = "Reverse transcriptase",
                translation = "逆转录酶",
                aliases = listOf("逆转录没", "反转录没", "你转录酶"),
                notes = "以 RNA 为模板合成互补 DNA（cDNA）的酶"
            ),

            // Translation & Protein Fate
            GlossaryTerm(
                canonicalTerm = "Ribosome",
                translation = "核糖体",
                aliases = listOf("核糖提", "合糖体", "ribasome"),
                notes = "由 rRNA 与核糖体蛋白组成的蛋白质合成场所（原核 70S，真核 80S）"
            ),
            GlossaryTerm(
                canonicalTerm = "Codon",
                translation = "密码子",
                aliases = listOf("密码只", "密马子"),
                notes = "mRNA 上决定特定氨基酸或终止信号的三联体碱基（如起始密码子 AUG）"
            ),
            GlossaryTerm(
                canonicalTerm = "Anticodon",
                translation = "反密码子",
                aliases = listOf("反密码只", "翻密码子"),
                notes = "tRNA 反密码环上与 mRNA 密码子互补配对的三个核苷酸"
            ),
            GlossaryTerm(
                canonicalTerm = "Open reading frame",
                translation = "开放阅读框",
                aliases = listOf("ORF", "开放阅读匡", "开放阅读矿"),
                notes = "从起始密码子到终止密码子之间的连续编码区"
            ),
            GlossaryTerm(
                canonicalTerm = "Chaperone",
                translation = "分子伴侣",
                aliases = listOf("分子伴旅", "分子半侣", "chaperon"),
                notes = "协助新生肽链正确折叠、防止错误聚集的蛋白质（如 Hsp70/GroEL）"
            ),
            GlossaryTerm(
                canonicalTerm = "Ubiquitination",
                translation = "泛素化",
                aliases = listOf("范素化", "饭素化", "泛速化", "ubiquitnation"),
                notes = "通过 E1/E2/E3 酶级联将泛素共价连接至靶蛋白以标记降解或调控信号"
            ),
            GlossaryTerm(
                canonicalTerm = "Proteasome",
                translation = "蛋白酶体",
                aliases = listOf("蛋白没体", "蛋白酶提", "proteosome"),
                notes = "识别并降解多聚泛素化标记蛋白的 26S 大分子复合物"
            ),
            GlossaryTerm(
                canonicalTerm = "Phosphorylation",
                translation = "磷酸化",
                aliases = listOf("林酸化", "临酸化", "磷算化", "phosphorilation"),
                notes = "激酶将磷酸基团转移至蛋白 Ser/Thr/Tyr 残基上的可逆共价修饰"
            ),
            GlossaryTerm(
                canonicalTerm = "Kinase",
                translation = "激酶",
                aliases = listOf("激没", "基酶", "机酶"),
                notes = "催化底物磷酸化修饰的关键信号转导酶"
            ),

            // Epigenetics & Non-coding RNA
            GlossaryTerm(
                canonicalTerm = "Epigenetics",
                translation = "表观遗传学",
                aliases = listOf("表观遗传", "表关遗传", "标观遗传", "epigenetic"),
                notes = "不改变 DNA 一级序列但可稳定遗传的基因表达调控机制"
            ),
            GlossaryTerm(
                canonicalTerm = "DNA methylation",
                translation = "DNA甲基化",
                aliases = listOf("假基化", "甲基画", "甲机化", "methylation"),
                notes = "常发生于 CpG 岛胞嘧啶 5 位碳（5mC），多与转录沉默相关"
            ),
            GlossaryTerm(
                canonicalTerm = "Histone acetylation",
                translation = "组蛋白乙酰化",
                aliases = listOf("乙酰化", "以酰化", "乙先化", "acetylation"),
                notes = "中和组蛋白赖氨酸正电荷，使染色质疏松并激活转录"
            ),
            GlossaryTerm(
                canonicalTerm = "microRNA",
                translation = "微小RNA",
                aliases = listOf("miRNA", "米RNA", "微RNA"),
                notes = "约 22 nt 非编码小 RNA，经 RISC 复合物介导靶 mRNA 降解或翻译抑制"
            ),
            GlossaryTerm(
                canonicalTerm = "RNA interference",
                translation = "RNA干扰",
                aliases = listOf("RNAi", "siRNA", "RNA干绕"),
                notes = "双链 RNA 诱导的序列特异性基因沉默机制"
            ),

            // Molecular Biology Techniques & Biotechnology
            GlossaryTerm(
                canonicalTerm = "CRISPR-Cas9",
                translation = "CRISPR基因编辑系统",
                aliases = listOf("CRISPR", "Cas9", "克里斯珀", "基因编集"),
                notes = "sgRNA 引导 Cas9 核酸内切酶识别 PAM 序列并精准切割靶向 DNA"
            ),
            GlossaryTerm(
                canonicalTerm = "PCR",
                translation = "聚合酶链式反应",
                aliases = listOf("聚合没链式反应", "qPCR", "RT-PCR"),
                notes = "经变性-退火-延伸循环在体外指数级扩增特定 DNA 片段"
            ),
            GlossaryTerm(
                canonicalTerm = "Electrophoresis",
                translation = "电泳",
                aliases = listOf("电永", "电咏", "琼脂糖凝胶电泳", "SDS-PAGE"),
                notes = "利用电场按分子大小和电荷分离核酸或蛋白质的技术"
            ),
            GlossaryTerm(
                canonicalTerm = "Western blot",
                translation = "蛋白质免疫印迹",
                aliases = listOf("蛋白印迹", "免疫印迹", "免疫印记", "western"),
                notes = "经电泳转膜与一抗/二抗孵育检测特定目标蛋白表达量"
            ),
            GlossaryTerm(
                canonicalTerm = "Plasmid",
                translation = "质粒",
                aliases = listOf("质立", "质利", "质粒载体"),
                notes = "细菌染色体外可自主复制的环状双链 DNA，常用作分子克隆载体"
            ),
            GlossaryTerm(
                canonicalTerm = "Restriction enzyme",
                translation = "限制性内切酶",
                aliases = listOf("限制性内切没", "内切没", "限制没"),
                notes = "识别特定回文序列并切割 DNA 产生黏性末端或平末端"
            ),
            GlossaryTerm(
                canonicalTerm = "Homologous recombination",
                translation = "同源重组",
                aliases = listOf("同原重组", "同源重祖", "HDR"),
                notes = "依赖同源模板精确修复 DNA 双链断裂或实现定向基因敲入"
            ),
            GlossaryTerm(
                canonicalTerm = "Apoptosis",
                translation = "细胞凋亡",
                aliases = listOf("雕亡", "细胞雕亡", "apoptosis"),
                notes = "由 Caspase 级联介导的程序性细胞死亡"
            ),
            GlossaryTerm(
                canonicalTerm = "Autophagy",
                translation = "细胞自噬",
                aliases = listOf("自试", "细胞自试", "autophagy"),
                notes = "溶酶体介导降解受损细胞器与错误折叠蛋白的胞内稳态机制"
            ),
            GlossaryTerm(
                canonicalTerm = "Allosteric regulation",
                translation = "别构调节",
                aliases = listOf("别构条节", "变构调节"),
                notes = "效应分子结合酶/蛋白的非活性位点引起构象变化从而调节活性"
            ),
            GlossaryTerm(
                canonicalTerm = "Riboswitch",
                translation = "核糖开关",
                aliases = listOf("核糖开光", "核糖关"),
                notes = "mRNA 5'-UTR 上直接结合小分子代谢物调控自身转录或翻译的元件"
            )
        )
    )

    val PHYSICS_STARTER = GlossaryPackExport(
        name = "大学物理与量子力学 (示例)",
        description = "含常见物理专业中英术语及远场易错同音词映射，可直接启用或扩充",
        terms = listOf(
            GlossaryTerm(
                canonicalTerm = "Hamiltonian",
                translation = "哈密顿量",
                aliases = listOf("hamitonian", "哈密顿良", "哈米顿量", "哈密顿亮"),
                notes = "系统总能量算符 H"
            ),
            GlossaryTerm(
                canonicalTerm = "Schrödinger",
                translation = "薛定谔",
                aliases = listOf("shrodinger", "schrodinger", "学定额", "薛定额", "血定额"),
                notes = "量子力学核心演化方程提出者"
            ),
            GlossaryTerm(
                canonicalTerm = "Eigenvalue",
                translation = "特征值",
                aliases = listOf("eignvalue", "艾根value", "特征只"),
                notes = "算符作用于本征态对应的本征值"
            ),
            GlossaryTerm(
                canonicalTerm = "Eigenstate",
                translation = "本征态",
                aliases = listOf("eignstate", "本真态", "本证态"),
                notes = "算符的本征矢量/本征函数"
            ),
            GlossaryTerm(
                canonicalTerm = "Lagrangian",
                translation = "拉格朗日量",
                aliases = listOf("lagragian", "拉格朗日良", "拉哥朗日"),
                notes = "分析力学中 L = T - V"
            ),
            GlossaryTerm(
                canonicalTerm = "Fourier transform",
                translation = "傅里叶变换",
                aliases = listOf("福利叶变换", "付里叶变换", "傅立叶变换"),
                notes = "时域/空域与频域/动量空间变换"
            ),
            GlossaryTerm(
                canonicalTerm = "Wavefunction",
                translation = "波函数",
                aliases = listOf("波寒数", "拨函数"),
                notes = "描述量子态的概率幅 ψ(r,t)"
            ),
            GlossaryTerm(
                canonicalTerm = "Commutator",
                translation = "对易子",
                aliases = listOf("对椅子", "对益子", "对易只"),
                notes = "[A, B] = AB - BA"
            ),
            GlossaryTerm(
                canonicalTerm = "Perturbation",
                translation = "微扰",
                aliases = listOf("为扰", "微绕"),
                notes = "微扰论近似方法"
            ),
            GlossaryTerm(
                canonicalTerm = "Hermitian",
                translation = "厄米特",
                aliases = listOf("厄米", "额米特", "hermition"),
                notes = "自伴算符，保证特征值为实数"
            )
        )
    )

    val CS_AI_STARTER = GlossaryPackExport(
        name = "计算机与人工智能 (示例)",
        description = "机器学习、系统与算法课程常用中英混杂术语",
        terms = listOf(
            GlossaryTerm(
                canonicalTerm = "Transformer",
                translation = "变换器模型",
                aliases = listOf("传思佛默", "变形金刚模型"),
                notes = "基于自注意力机制的神经网络架构"
            ),
            GlossaryTerm(
                canonicalTerm = "Backpropagation",
                translation = "反向传播",
                aliases = listOf("backprop", "反向传波"),
                notes = "链式法则计算梯度"
            ),
            GlossaryTerm(
                canonicalTerm = "Gradient Descent",
                translation = "梯度下降",
                aliases = listOf("踢度下降", "梯度下讲"),
                notes = "沿负梯度方向优化损失函数"
            ),
            GlossaryTerm(
                canonicalTerm = "Regularization",
                translation = "正则化",
                aliases = listOf("正折化", "正则画"),
                notes = "抑制过拟合的惩罚项"
            ),
            GlossaryTerm(
                canonicalTerm = "Attention",
                translation = "注意力机制",
                aliases = listOf("阿藤申"),
                notes = "Scaled Dot-Product Attention"
            ),
            GlossaryTerm(
                canonicalTerm = "Embedding",
                translation = "词嵌入",
                aliases = listOf("in bedding", "词千入"),
                notes = "高维稠密向量表示"
            )
        )
    )

    val ALL_STARTERS = listOf(MOLECULAR_BIOLOGY_STARTER, PHYSICS_STARTER, CS_AI_STARTER)
}
