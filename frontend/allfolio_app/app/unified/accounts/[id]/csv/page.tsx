'use client'

import { useState, useRef } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useUnifiedApi } from '@/lib/useApi'
import PageHeader from '@/components/ui/PageHeader'
import SectionHeader from '@/components/ui/SectionHeader'
import Button from '@/components/ui/Button'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { LoadingState } from '@/components/ui/states'
import type { CsvPreviewRow, CsvImportResult } from '@/types/unified'

const CSV_TEMPLATE = `name,symbol,type,quantity,purchasePrice,currentValue,currency,memo
삼성전자,005930,STOCK,10,70000,75000,KRW,보통주
비트코인,BTC,CRYPTO,0.5,30000000,45000000,KRW,장기보유
강남아파트,,REAL_ESTATE,1,800000000,950000000,KRW,
현금,,CASH,5000000,5000000,5000000,KRW,비상금`

const PREVIEW_GRID = 'grid grid-cols-[36px_1.3fr_0.9fr_0.9fr_0.7fr_0.9fr_0.9fr_1.3fr] gap-3'

export default function CsvUploadPage() {
  const { id } = useParams<{ id: string }>()
  const router   = useRouter()
  const qc       = useQueryClient()
  const fileRef  = useRef<HTMLInputElement>(null)
  const api      = useUnifiedApi()

  const [file, setFile]           = useState<File | null>(null)
  const [preview, setPreview]     = useState<CsvPreviewRow[] | null>(null)
  const [result, setResult]       = useState<CsvImportResult | null>(null)
  const [loading, setLoading]     = useState(false)
  const [dragging, setDragging]   = useState(false)

  const handleFile = async (f: File) => {
    setFile(f)
    setResult(null)
    setLoading(true)
    try {
      const rows = await api!.accounts.previewCsv(id, f)
      setPreview(rows)
    } catch (e) {
      alert('미리보기 실패: ' + (e as Error).message)
    } finally {
      setLoading(false)
    }
  }

  const handleImport = async () => {
    if (!file) return
    setLoading(true)
    try {
      const res = await api!.accounts.importCsv(id, file)
      setResult(res)
      qc.invalidateQueries({ queryKey: ['unified', 'account-assets', id] })
      qc.invalidateQueries({ queryKey: ['unified', 'portfolio'] })
    } catch (e) {
      alert('가져오기 실패: ' + (e as Error).message)
    } finally {
      setLoading(false)
    }
  }

  const downloadTemplate = () => {
    const blob = new Blob([CSV_TEMPLATE], { type: 'text/csv;charset=utf-8' })
    const url  = URL.createObjectURL(blob)
    const a    = document.createElement('a')
    a.href     = url
    a.download = 'asset_template.csv'
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div className="border border-line-card bg-surface">
      <div className="px-5 pt-4 sm:px-7">
        <Link
          href={`/unified/accounts/${id}`}
          className="font-mono text-[10px] tracking-label text-fg-faint transition-colors hover:text-ink"
        >
          ← 계좌 상세로
        </Link>
      </div>
      <PageHeader
        className="px-5 pt-2 sm:px-7"
        title="CSV 업로드"
        meta="자산 내역 CSV 파일을 업로드해 가져옵니다"
      />

      <div className="space-y-6 px-5 py-5 pb-10 sm:px-7">

        {/* Template Download */}
        <div className="border border-line bg-surface-muted p-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <Label size="sm">CSV 형식 안내</Label>
              <p className="mt-1.5 font-mono text-[10.5px] tracking-[0.02em] text-fg-3">
                name, symbol, type, quantity, purchasePrice, currentValue, currency, memo
              </p>
              <p className="mt-1 font-mono text-[10px] tracking-[0.02em] text-fg-faint">
                지원 type: STOCK / CRYPTO / REAL_ESTATE / VEHICLE / GOLD / CASH / ETC
              </p>
            </div>
            <Button size="sm" className="shrink-0" onClick={downloadTemplate}>
              템플릿 다운로드
            </Button>
          </div>
        </div>

        {/* Drop Zone */}
        {!result && (
          <div
            onDragOver={e => { e.preventDefault(); setDragging(true) }}
            onDragLeave={() => setDragging(false)}
            onDrop={e => {
              e.preventDefault()
              setDragging(false)
              const f = e.dataTransfer.files[0]
              if (f) handleFile(f)
            }}
            onClick={() => fileRef.current?.click()}
            className={`cursor-pointer border-2 border-dashed p-12 text-center transition-colors ${
              dragging
                ? 'border-ink bg-surface-muted'
                : 'border-line hover:border-ink'
            }`}
          >
            <input
              ref={fileRef}
              type="file"
              accept=".csv,text/csv"
              className="hidden"
              onChange={e => { const f = e.target.files?.[0]; if (f) handleFile(f) }}
            />
            <p className="text-sm text-fg-2">
              {file ? file.name : 'CSV 파일을 드래그하거나 클릭해서 선택'}
            </p>
            <p className="mt-1 text-xs text-fg-faint">최대 10MB</p>
          </div>
        )}

        {/* Loading */}
        {loading && <LoadingState label="처리 중" />}

        {/* Preview */}
        {preview && !result && !loading && (
          <div>
            <SectionHeader
              label="미리보기"
              note={`${preview.length}행`}
              actions={
                <div className="flex flex-wrap gap-2">
                  <Button
                    size="sm"
                    onClick={() => { setFile(null); setPreview(null); if (fileRef.current) fileRef.current.value = '' }}
                  >
                    다시 선택
                  </Button>
                  <Button
                    size="sm"
                    variant="primary"
                    onClick={handleImport}
                    disabled={preview.filter(r => !r.error).length === 0}
                  >
                    가져오기 ({preview.filter(r => !r.error).length}개
                    {preview.some(r => r.error) && ` / ${preview.filter(r => r.error).length}개 오류 건너뜀`})
                  </Button>
                </div>
              }
            />

            <div className="overflow-x-auto">
              <div className="min-w-[760px] border-t-[1.5px] border-ink">
                <div className={`${PREVIEW_GRID} border-b border-line py-2`}>
                  <Label size="sm" tone="faint">#</Label>
                  <Label size="sm" tone="faint">이름</Label>
                  <Label size="sm" tone="faint">심볼</Label>
                  <Label size="sm" tone="faint">유형</Label>
                  <Label size="sm" tone="faint" className="text-right">수량</Label>
                  <Label size="sm" tone="faint" className="text-right">매입가</Label>
                  <Label size="sm" tone="faint" className="text-right">현재가치</Label>
                  <Label size="sm" tone="faint">오류</Label>
                </div>
                {preview.map(row => (
                  <div
                    key={row.line}
                    className={`${PREVIEW_GRID} items-baseline border-b border-line-hair py-2.5 hover:bg-surface-muted`}
                  >
                    <Num className="text-[11px] text-fg-faint">{row.line}</Num>
                    <span className="truncate text-[13px]">{row.name}</span>
                    <span className="truncate font-mono text-[10.5px] tracking-[0.04em] text-fg-3">{row.symbol}</span>
                    <span className="font-mono text-[10px] tracking-label text-fg-3">{row.type}</span>
                    <Num className="text-right text-[12px] text-fg-3">{row.quantity}</Num>
                    <Num className="text-right text-[12px] text-fg-3">{row.purchasePrice}</Num>
                    <Num className="text-right text-[12px] text-fg-3">{row.currentValue}</Num>
                    <span className="text-xs text-danger">{row.error}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}

        {/* Import Result */}
        {result && (
          <div className="border border-ink bg-surface-muted p-5 sm:p-6">
            <SectionHeader label="가져오기 완료" />
            <div className="flex gap-10">
              <div>
                <Label size="sm" tone="faint">성공</Label>
                <Num className="mt-1 block text-[15px] text-ok">{result.imported}개</Num>
              </div>
              <div>
                <Label size="sm" tone="faint">건너뜀</Label>
                <Num className="mt-1 block text-[15px] text-warn">{result.skipped}개</Num>
              </div>
            </div>
            {result.errors.length > 0 && (
              <div className="mt-4 border border-line bg-surface px-4 py-2.5">
                <Label size="sm" className="text-danger">오류 목록</Label>
                {result.errors.map((e, i) => (
                  <p key={i} className="mt-1 text-xs text-danger">{e}</p>
                ))}
              </div>
            )}
            <div className="mt-5 flex gap-2.5">
              <Button variant="primary" onClick={() => router.push(`/unified/accounts/${id}`)}>
                계좌 상세 보기
              </Button>
              <Button onClick={() => { setFile(null); setPreview(null); setResult(null) }}>
                다시 업로드
              </Button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
