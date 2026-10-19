export function createPoller<T>(
  request: () => Promise<T>,
  publish: (value: T) => void,
  interval = 5000,
) {
  let active = false
  let inFlight = false
  let requested = false
  let revision = 0
  let timer: ReturnType<typeof setTimeout> | undefined

  function refresh() {
    revision++
    clearTimeout(timer)
    if (!active) return
    if (inFlight) { requested = true; return }
    inFlight = true
    requested = false
    const current = revision
    void request().then(value => {
      if (active && current === revision) publish(value)
    }).catch(() => {
      // 一次读取失败不覆盖已有状态，也不终止后续轮询。
    }).finally(() => {
      inFlight = false
      if (!active) return
      if (requested) refresh()
      else timer = setTimeout(refresh, interval)
    })
  }

  return {
    refresh,
    setActive(value: boolean) {
      if (active === value) return
      active = value
      revision++
      clearTimeout(timer)
      if (active) refresh()
    },
  }
}
