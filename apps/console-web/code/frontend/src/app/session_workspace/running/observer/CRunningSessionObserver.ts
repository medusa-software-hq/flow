import { create } from '@bufbuild/protobuf';
import { proxy } from 'valtio';
import {
  GrpcControlServiceGetRunningFlowProgressRequestSchema,
  PbTaskExecutionProgress,
} from '@/gen/medusa/flow/control_service/v1/grpc_control_service_pb';
import { CoreServiceClient } from '@/rpc/myGrpcTypes';
import { sleep } from '@/utils/promiseUtils';
import { IRunningSessionObserver, ITaskProgressSnapshot } from './IRunningSessionObserver';

const pollingIntervalMs = 2000;

export class CRunningSessionObserver implements IRunningSessionObserver {
  static observe(args: { coreServiceClient: CoreServiceClient; sessionId: string }) {
    const self = proxy(new CRunningSessionObserver(args));

    void self._startPolling();

    return self;
  }

  private readonly _coreServiceClient: CoreServiceClient;
  private readonly _sessionId: string;

  private _lastPolledSnapshot: ITaskProgressSnapshot | null = null;

  private constructor(args: { coreServiceClient: CoreServiceClient; sessionId: string }) {
    this._coreServiceClient = args.coreServiceClient;
    this._sessionId = args.sessionId;
  }

  get lastReceivedSnapshot(): ITaskProgressSnapshot | null {
    return this._lastPolledSnapshot;
  }

  private async _startPolling(): Promise<void> {
    while (true) {
      try {
        const sessionId = this._sessionId;

        console.info(`Polling running session progress for session ID '${sessionId}'...`);

        const response = await this._coreServiceClient.getRunningFlowProgress(
          create(GrpcControlServiceGetRunningFlowProgressRequestSchema, {
            flowId: sessionId,
          })
        );

        const polledSnapshot: ITaskProgressSnapshot = {
          progressByTaskId: new Map(
            response.runningFlowProgress?.taskExecutionProgresses.map(
              (taskProgress: PbTaskExecutionProgress) => [
                taskProgress.taskId,
                taskProgress.progress,
              ]
            ) ?? []
          ),
        };

        this._lastPolledSnapshot = polledSnapshot;
      } catch (e: unknown) {
        console.error('Error while polling running session progress:', e);

        this._lastPolledSnapshot = null;
      }

      await sleep(pollingIntervalMs);
    }
  }
}
