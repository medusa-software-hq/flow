import { create } from '@bufbuild/protobuf';
import {
  PbFeatureTaskDefinition,
  PbFeatureTaskDefinitionSchema,
  PbMergeTaskDefinition,
  PbMergeTaskDefinitionSchema,
} from '@/gen/medusa/flow/control_service/v1/grpc_control_service_pb';

export const TaskDefinitionKinds = {
  Feature: 'feature',
  Merge: 'merge',
} as const;
export type TTaskDefinitionKind = (typeof TaskDefinitionKinds)[keyof typeof TaskDefinitionKinds];

export interface ITaskDefinition {
  readonly kind: TTaskDefinitionKind;
}

export interface IFeatureTaskDefinition extends ITaskDefinition {
  readonly kind: typeof TaskDefinitionKinds.Feature;
  readonly label: string;
  readonly description: string;
}

export interface IMergeTaskDefinition extends ITaskDefinition {
  readonly kind: typeof TaskDefinitionKinds.Merge;
}

export const MergeTaskDefinition: IMergeTaskDefinition = {
  kind: TaskDefinitionKinds.Merge,
};

export type UTaskDefinition = IFeatureTaskDefinition | IMergeTaskDefinition;

export type PbTaskDefinition =
  | {
      value: PbFeatureTaskDefinition;
      case: 'featureTask';
    }
  | {
      value: PbMergeTaskDefinition;
      case: 'mergeTask';
    }
  | { case: undefined; value?: undefined };

export const TaskDefinitionUtils = {
  load(pbTaskDefinition: PbTaskDefinition): UTaskDefinition {
    switch (pbTaskDefinition.case) {
      case 'featureTask': {
        const featureTaskDefinition: IFeatureTaskDefinition = {
          kind: TaskDefinitionKinds.Feature,
          label: pbTaskDefinition.value.label,
          description: pbTaskDefinition.value.description,
        };

        return featureTaskDefinition;
      }

      case 'mergeTask': {
        return MergeTaskDefinition;
      }

      default: {
        throw new Error(`Unsupported task definition kind: ${pbTaskDefinition.case}`);
      }
    }
  },

  dump(taskDefinition: UTaskDefinition): PbTaskDefinition {
    switch (taskDefinition.kind) {
      case TaskDefinitionKinds.Feature: {
        return {
          case: 'featureTask',
          value: create(PbFeatureTaskDefinitionSchema, {
            label: taskDefinition.label,
            description: taskDefinition.description,
          }),
        };
      }

      case TaskDefinitionKinds.Merge: {
        return {
          case: 'mergeTask',
          value: create(PbMergeTaskDefinitionSchema, {}),
        };
      }

      default: {
        throw new Error(`Unsupported task definition kind: ${taskDefinition}`);
      }
    }
  },
};
