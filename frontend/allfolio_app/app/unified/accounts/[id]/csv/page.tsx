'use client'

import { useState, useRef } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { useQueryClient } from '@tanstack/react-query'
import Link from 'next/link'
import { useUnifiedApi } from '@/lib/useApi'
import type { CsvPreviewRow, CsvImportResult } from '@/types/unified'

const CSV_TEMPLATE = `name,symbol,type,quantity,purchasePrice,currentValue,currency,memo
삼성전자,005930,STOCK,10,70000,75000,KRW,보통주
비트코인,BTC,CRYPTO,0.5,30000000,45000000,KRW,장기보유
강남아파트,,REAL_ESTATE,1,800000000,950000000,KRW,
현금,,CASH,5000000,5000000,5000000,KRW,비상금`

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
    <div className="mx-auto max-w-2xl space-y-6">
      <div>
        <Link href={`/unified/accounts/${id}`} className="mb-2 inline-block text-xs text-gray-500 hover:text-gray-300">
          ← 계좌 상세로
        </Link>
        <h1 className="text-2xl font-bold">CSV 업로드</h1>
        <p className="mt-1 text-sm text-gray-400">자산 내역 CSV 파일을 업로드해 가져옵니다</p>
      </div>

      {/* Template Download */}
      <div className="rounded-xl border border-gray-700 bg-gray-900 p-4">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-sm font-medium">CSV 형식 안내</p>
            <p className="mt-0.5 text-xs text-gray-500">
              name, symbol, type, quantity, purchasePrice, currentValue, currency, memo
            </p>
            <p className="mt-1 text-xs text-gray-600">
              지원 type: STOCK / CRYPTO / REAL_ESTATE / VEHICLE / GOLD / CASH / ETC
            </p>
          </div>
          <button
            onClick={downloadTemplate}
            className="shrink-0 rounded-lg border border-gray-600 px-3 py-1.5 text-xs hover:border-gray-400 transition-colors"
          >
            템플릿 다운로드
          </button>
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
          className={`cursor-pointer rounded-xl border-2 border-dashed p-12 text-center transition-all ${
            dragging
              ? 'border-blue-500 bg-blue-950/20'
              : 'border-gray-700 hover:border-gray-500'
          }`}
        >
          <input
            ref={fileRef}
            type="file"
            accept=".csv,text/csv"
            className="hidden"
            onChange={e => { const f = e.target.files?.[0]; if (f) handleFile(f) }}
          />
          <p className="text-2xl">📂</p>
          <p className="mt-2 text-sm text-gray-300">
            {file ? file.name : 'CSV 파일을 드래그하거나 클릭해서 선택'}
          </p>
          <p className="mt-1 text-xs text-gray-500">최대 10MB</p>
        </div>
      )}

      {/* Loading */}
      {loading && (
        <div className="flex items-center gap-2 text-sm text-gray-400">
          <span className="animate-spin">⟳</span> 처리 중…
        </div>
      )}

      {/* Preview */}
      {preview && !result && !loading && (
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold text-gray-300">
              미리보기 ({preview.length}행)
            </h2>
            <div className="flex gap-2">
              <button
                onClick={() => { setFile(null); setPreview(null); if (fileRef.current) fileRef.current.value = '' }}
                className="rounded-lg border border-gray-600 px-3 py-1.5 text-xs hover:border-gray-400 transition-colors"
              >
                다시 선택
              </button>
              <button
                onClick={handleImport}
                disabled={preview.filter(r => !r.error).length === 0}
                className="rounded-lg bg-blue-600 px-4 py-1.5 text-sm font-medium hover:bg-blue-500 disabled:opacity-50 transition-colors"
              >
                가져오기 ({preview.filter(r => !r.error).length}개
                {preview.some(r => r.error) && ` / ${preview.filter(r => r.error).length}개 오류 건너뜀`})
              </button>
            </div>
          </div>

          <div className="overflow-x-auto rounded-xl border border-gray-700">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-gray-700 bg-gray-800 text-left text-gray-400">
                  <th className="px-3 py-2">#</th>
                  <th className="px-3 py-2">이름</th>
                  <th className="px-3 py-2">심볼</th>
                  <th className="px-3 py-2">유형</th>
                  <th className="px-3 py-2 text-right">수량</th>
                  <th className="px-3 py-2 text-right">매입가</th>
                  <th className="px-3 py-2 text-right">현재가치</th>
                  <th className="px-3 py-2">오류</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-800">
                {preview.map(row => (
                  <tr key={row.line} className={row.error ? 'bg-red-950/20' : 'bg-gray-900'}>
                    <td className="px-3 py-2 text-gray-500">{row.line}</td>
                    <td className="px-3 py-2">{row.name}</td>
                    <td className="px-3 py-2 text-gray-400">{row.symbol}</td>
                    <td className="px-3 py-2">{row.type}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{row.quantity}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{row.purchasePrice}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{row.currentValue}</td>
                    <td className="px-3 py-2 text-red-400">{row.error}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Import Result */}
      {result && (
        <div className="rounded-xl border border-emerald-800 bg-emerald-950 p-6 space-y-3">
          <p className="font-semibold text-emerald-400">✓ 가져오기 완료</p>
          <div className="flex gap-6 text-sm">
            <div>
              <p className="text-xs text-gray-400">성공</p>
              <p className="text-xl font-bold text-emerald-400">{result.imported}개</p>
            </div>
            <div>
              <p className="text-xs text-gray-400">건너뜀</p>
              <p className="text-xl font-bold text-yellow-400">{result.skipped}개</p>
            </div>
          </div>
          {result.errors.length > 0 && (
            <div className="rounded-lg border border-red-800 bg-red-950 p-3">
              <p className="mb-1 text-xs font-medium text-red-400">오류 목록</p>
              {result.errors.map((e, i) => (
                <p key={i} className="text-xs text-red-500">{e}</p>
              ))}
            </div>
          )}
          <div className="flex gap-3 pt-2">
            <button
              onClick={() => router.push(`/unified/accounts/${id}`)}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium hover:bg-blue-500 transition-colors"
            >
              계좌 상세 보기
            </button>
            <button
              onClick={() => { setFile(null); setPreview(null); setResult(null) }}
              className="rounded-lg border border-gray-600 px-4 py-2 text-sm hover:border-gray-400 transition-colors"
            >
              다시 업로드
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
