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
      },
    },
  },
}

export default config
