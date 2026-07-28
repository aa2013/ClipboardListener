import { appTasks } from '@ohos/hvigor-ohos-plugin';

export default {
    system: appTasks,  /* Hvigor 内置应用任务，旧版 Flutter 构建路径会直接复用该任务。 */
    plugins:[]         /* 当前 SDK 未提供自定义构建插件包入口，保持为空以走 Dart builder。 */
}
