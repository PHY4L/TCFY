package com.phy4l.tcfy.glossary

/**
 * Starter glossary packs pre-seeded on first launch so users can test immediately
 * or use them as templates for their own courses.
 */
object BuiltInGlossaries {
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

    val ALL_STARTERS = listOf(PHYSICS_STARTER, CS_AI_STARTER)
}
