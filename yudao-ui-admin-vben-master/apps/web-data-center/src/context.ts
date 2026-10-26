import type { InjectionKey, ShallowReactive } from 'vue';

import type { DataCenterProps } from '@vben/micro-apps';

export const dataCenterKey: InjectionKey<
  ShallowReactive<{ props: DataCenterProps }>
> = Symbol('data-center');
