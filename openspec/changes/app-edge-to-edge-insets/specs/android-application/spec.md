# Android Application Specification — Delta: app-edge-to-edge-insets

## ADDED Requirements

### Requirement: App 返回操作兼容 Android 14/15 预测性返回

App MUST 显式允许系统 Back Invoked 集成，并通过 AndroidX `OnBackPressedDispatcher`/`OnBackPressedCallback` 处理自定义 Activity 返回行为。Activity MUST NOT 通过覆盖 `onBackPressed()` 拦截系统回退；MUST 保留未保存修改确认、编辑草稿取消和有结果 Activity 的既有语义。若返回不需要业务拦截，回调 SHOULD 禁用，以让系统默认导航与预测动画继续处理。

#### Scenario: 清洁编辑页执行预测性返回

- **GIVEN** 用户正在一个没有未保存修改的配置编辑 Activity
- **WHEN** 用户从系统手势边缘返回
- **THEN** 默认 Activity 回退路径完成页面导航
- **AND** App 不以旧式 `onBackPressed()` 覆盖阻断系统 Back dispatcher

#### Scenario: 无业务拦截的资源页保留默认返回路径

- **GIVEN** 用户正在 Assets 页面，且该页面没有待确认的业务返回动作
- **WHEN** 用户使用系统返回手势或工具栏 Home
- **THEN** Activity 不注册始终启用的自定义 Back callback
- **AND** 工具栏 Home 交由 AndroidX dispatcher 的默认回退路径处理

#### Scenario: 有未保存修改时返回

- **GIVEN** 用户编辑配置并产生未保存修改
- **WHEN** 用户提交系统 Back 手势
- **THEN** AndroidX Back dispatcher 显示既有未保存修改确认
- **AND** 取消保留编辑内容，确认保存/放弃的行为与原有结果一致

#### Scenario: 自定义 Activity 返回结果

- **GIVEN** 用户在节点优先级页面调整顺序或移除成员
- **WHEN** 用户通过系统 Back 返回
- **THEN** Activity 通过 AndroidX dispatcher 返回既有顺序/排除/成员结果

#### Scenario: 节点优先级页工具栏 Home 保持结果返回

- **GIVEN** 用户在节点优先级页修改了顺序、排除成员或散装成员
- **WHEN** 用户点击工具栏 Home
- **THEN** Home 操作通过 AndroidX dispatcher 触发现有结果回调
- **AND** Activity 返回与系统 Back 相同的顺序/排除/成员结果
