import type { CodegenConfig } from '@graphql-codegen/cli'

const config: CodegenConfig = {
  schema: '../server/graph/schema.graphqls',
  documents: 'src/api/operations/**/*.graphql',
  generates: {
    'src/api/gen/': {
      preset: 'client',
      presetConfig: {
        fragmentMasking: false,
      },
      config: {
        scalars: {
          Time: 'string',
          Int64: 'number',
        },
        useTypeImports: true,
        enumsAsTypes: true,
        // 查询以字符串(TypedDocumentString)输出,不再是一大坨 AST 对象:包小一截,
        // 请求层也不用再 print(),graphql 运行时库可以不进浏览器
        documentMode: 'string',
      },
    },
  },
}

export default config
